package dev.lumjahaj.subscription.hub.dunning.app;

import dev.lumjahaj.subscription.hub.audit.app.AuditService;
import dev.lumjahaj.subscription.hub.audit.domain.AuditEventType;
import dev.lumjahaj.subscription.hub.billing.domain.InvoiceRepository;
import dev.lumjahaj.subscription.hub.billing.domain.InvoiceStatus;
import dev.lumjahaj.subscription.hub.billing.infra.jpa.InvoiceEntity;
import dev.lumjahaj.subscription.hub.common.metrics.AfterCommit;
import dev.lumjahaj.subscription.hub.dunning.domain.DunningStateRepository;
import dev.lumjahaj.subscription.hub.dunning.infra.jpa.DunningStateEntity;
import dev.lumjahaj.subscription.hub.notification.app.NotificationService;
import dev.lumjahaj.subscription.hub.payment.domain.PaymentOutcomeListener;
import dev.lumjahaj.subscription.hub.payment.domain.PaymentRepository;
import dev.lumjahaj.subscription.hub.payment.domain.PaymentStatus;
import dev.lumjahaj.subscription.hub.subscription.domain.SubscriptionRepository;
import dev.lumjahaj.subscription.hub.subscription.domain.SubscriptionStatus;
import dev.lumjahaj.subscription.hub.subscription.infra.jpa.SubscriptionEntity;
import dev.lumjahaj.subscription.hub.tenancy.domain.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The dunning rules: what a failed or successful collection means for the
 * invoice and the subscription behind it.
 *
 * It reacts to outcomes rather than producing them. DunningJob decides
 * *when* to attempt a payment; PaymentSettlementService decides whether one
 * succeeded; this decides what that says about the customer's standing. The
 * three are separate because settlement is asynchronous — with a real
 * provider the job has long since finished by the time a webhook arrives.
 */
@Service
public class DunningService implements PaymentOutcomeListener {

    private static final Logger log = LoggerFactory.getLogger(DunningService.class);
    private static final int MAX_TRANSITION_ATTEMPTS = 3;

    private final DunningStateRepository dunningStates;
    private final InvoiceRepository invoices;
    private final SubscriptionRepository subscriptions;
    private final PaymentRepository payments;
    private final DunningSchedule schedule;
    private final NotificationService notificationService;
    private final AuditService audit;
    private final MeterRegistry registry;

    public DunningService(
            DunningStateRepository dunningStates,
            InvoiceRepository invoices,
            SubscriptionRepository subscriptions,
            PaymentRepository payments,
            DunningSchedule schedule,
            NotificationService notificationService,
            AuditService audit,
            MeterRegistry registry
    ) {
        this.dunningStates = dunningStates;
        this.invoices = invoices;
        this.subscriptions = subscriptions;
        this.payments = payments;
        this.schedule = schedule;
        this.notificationService = notificationService;
        this.audit = audit;
        this.registry = registry;
    }

    /**
     * Collected. The retry schedule is deleted rather than kept for history —
     * the payments themselves are the record of what happened — and a
     * subscription held back by non-payment starts earning again.
     *
     * It does not renew the subscription here: the next BillingCycleJob run
     * finds it due, is told by the unique period key that this period is
     * already invoiced, and renews on that existing partial-run path.
     */
    @Override
    @Transactional
    public void onPaymentSucceeded(UUID invoiceId) {
        String tenantId = TenantContext.getTenantId();
        Optional<DunningStateEntity> state = dunningStates.findByTenantIdAndInvoiceId(tenantId, invoiceId);
        state.ifPresent(dunningStates::delete);
        // Only an invoice that was actually in dunning is a recovery; one paid
        // on first try never had a schedule. Tagged with how many automatic
        // attempts it took (0: a manual payment after a failure, before the
        // job ran), which is the number that says whether the retry delays
        // are worth their provider fees. Bounded by dunning.max-attempts.
        state.ifPresent(s -> {
            String attempts = String.valueOf(s.getAttemptCount());
            AfterCommit.run(() -> Counter.builder("dunning.recoveries")
                    .description("Invoices in dunning that were eventually paid")
                    .tag("attempts", attempts)
                    .register(registry)
                    .increment());
        });

        invoices.findByTenantIdAndId(tenantId, invoiceId).ifPresent(invoice -> {
            SubscriptionEntity subscription = invoice.getSubscription();
            // Only PAST_DUE recovers. A customer who canceled while this
            // payment was settling stays canceled: the old load-modify-save
            // would have written ACTIVE over that cancellation.
            transitionSubscription(tenantId, subscription, EnumSet.of(SubscriptionStatus.PAST_DUE),
                    from -> subscriptions.updateStatusIfStatus(tenantId, subscription.getId(), from, SubscriptionStatus.ACTIVE))
                    .ifPresent(from -> {
                        audit.recordSystem(AuditEventType.SUBSCRIPTION_RECOVERED, subscription.getId(),
                                Map.of("invoiceId", invoiceId));
                        log.info("Subscription {} recovered from PAST_DUE after invoice {} was paid",
                                subscription.getId(), invoice.getNumber());
                        // Here rather than beside the recovery counter above:
                        // only a real PAST_DUE -> ACTIVE transition means the
                        // customer had been told something was wrong. A first
                        // attempt that simply succeeds has a dunning row too,
                        // and nobody was ever emailed about it.
                        notificationService.enqueuePaymentRecovered(invoice);
                    });
        });
    }

    /**
     * Declined. The first failure makes the subscription PAST_DUE — a
     * customer who owes money is in a different state from one who doesn't,
     * and dunning needs somewhere to put them that renewal already refuses
     * to act on.
     *
     * When the attempts configured are used up, the invoice becomes
     * UNCOLLECTIBLE and the subscription is CANCELED. That is the deliberate
     * end of the process rather than retrying forever: each attempt costs a
     * provider fee and annoys the customer's bank.
     */
    @Override
    @Transactional
    public void onPaymentFailed(UUID invoiceId, String failureCode) {
        String tenantId = TenantContext.getTenantId();
        InvoiceEntity invoice = invoices.findByTenantIdAndId(tenantId, invoiceId).orElse(null);
        if (invoice == null) {
            log.warn("Ignoring failed payment for unknown invoice {}", invoiceId);
            return;
        }

        // A manual payment can fail before the job has ever seen this
        // invoice, so the schedule may not exist yet; starting it here means
        // a declined manual attempt is retried automatically from then on.
        DunningStateEntity state = dunningStates.findByTenantIdAndInvoiceId(tenantId, invoiceId)
                .orElseGet(() -> newState(tenantId, invoice, Instant.now()));
        state.setLastFailureCode(failureCode);

        if (schedule.isExhausted(state.getAttemptCount())) {
            giveUp(invoice, state, failureCode);
            return;
        }

        dunningStates.save(state);
        markPastDue(invoice);
        // state.getNextAttemptAt() already reflects the schedule's next
        // slot: startAttempt set it before the provider was ever called, so
        // there is nothing left to compute here, only to tell the customer.
        notificationService.enqueuePaymentFailed(invoice, state.getAttemptCount(), state.getNextAttemptAt());
    }

    /**
     * Claims the next collection attempt for an invoice, or returns empty
     * when there is nothing to do: not due yet, already settled, a payment
     * still in flight, or no stored payment method to charge.
     *
     * Lives here rather than in DunningJob because it must be a real
     * transaction: a @Transactional method called from another method of
     * the same bean bypasses the proxy entirely and silently runs without
     * one. The job calls this, commits, and only then talks to the provider.
     *
     * The attempt is counted before the provider is called, so a crash in
     * between costs one retry rather than leaving the invoice due again
     * immediately — which, on an hourly cron, would mean charging the
     * customer every hour.
     */
    @Transactional
    public Optional<DunningAttempt> startAttempt(String tenantId, UUID invoiceId, Instant now) {
        InvoiceEntity invoice = invoices.findByTenantIdAndId(tenantId, invoiceId).orElse(null);
        if (invoice == null || invoice.getStatus() != InvoiceStatus.OPEN) {
            return Optional.empty();
        }

        // The schedule starts when the invoice is issued, so the first
        // attempt needs no special case: a row created with
        // nextAttemptAt = issuedAt is due immediately.
        DunningStateEntity state = dunningStates.findByTenantIdAndInvoiceId(tenantId, invoiceId)
                .orElseGet(() -> newState(tenantId, invoice, invoice.getIssuedAt()));
        if (state.getNextAttemptAt().isAfter(now)) {
            return Optional.empty();
        }

        if (hasPaymentInFlight(tenantId, invoiceId)) {
            // A payment awaiting its webhook is not a failure, and the
            // in-flight index would reject a second one anyway.
            log.debug("Skipping invoice {}: a payment is already in flight", invoice.getNumber());
            return Optional.empty();
        }

        String paymentMethod = invoice.getCustomer().getDefaultPaymentMethod();
        if (paymentMethod == null) {
            // Nothing to charge with. Left OPEN for a human to collect
            // rather than written off.
            log.info("Skipping invoice {}: customer {} has no stored payment method",
                    invoice.getNumber(), invoice.getCustomer().getId());
            return Optional.empty();
        }

        int attemptNumber = state.getAttemptCount() + 1;
        state.setAttemptCount(attemptNumber);
        state.setNextAttemptAt(schedule.nextAttemptAt(attemptNumber, now));
        dunningStates.save(state);
        AfterCommit.run(() -> Counter.builder("dunning.attempts.started")
                .description("Automatic collection attempts claimed, before the provider is called")
                .register(registry)
                .increment());

        // Deterministic, so re-running the job after a crash resumes the
        // same attempt at the provider instead of charging twice.
        return Optional.of(new DunningAttempt(
                attemptNumber, paymentMethod, "dunning:" + invoiceId + ":" + attemptNumber));
    }

    private boolean hasPaymentInFlight(String tenantId, UUID invoiceId) {
        return payments.findByTenantIdAndInvoiceId(tenantId, invoiceId).stream()
                .anyMatch(payment -> payment.getStatus() == PaymentStatus.PENDING);
    }

    /** Creates the schedule for an invoice the job has not started dunning yet. */
    DunningStateEntity newState(String tenantId, InvoiceEntity invoice, Instant firstAttemptAt) {
        DunningStateEntity state = new DunningStateEntity();
        state.setTenantId(tenantId);
        state.setInvoice(invoice);
        state.setAttemptCount(0);
        state.setNextAttemptAt(firstAttemptAt);
        return state;
    }

    private void markPastDue(InvoiceEntity invoice) {
        String tenantId = invoice.getTenantId();
        SubscriptionEntity subscription = invoice.getSubscription();
        // CANCELED stays canceled, and PAUSED is a deliberate customer
        // choice that non-payment shouldn't quietly overwrite - including a
        // pause that commits while this failure is being settled.
        transitionSubscription(tenantId, subscription, EnumSet.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.TRIALING),
                from -> subscriptions.updateStatusIfStatus(tenantId, subscription.getId(), from, SubscriptionStatus.PAST_DUE))
                .ifPresent(from -> {
                    audit.recordSystem(AuditEventType.SUBSCRIPTION_PAST_DUE, subscription.getId(), Map.of(
                            "from", from,
                            "invoiceId", invoice.getId()));
                    log.info("Subscription {} is PAST_DUE after a failed payment for invoice {}",
                            subscription.getId(), invoice.getNumber());
                });
    }

    /**
     * Applies a status change to a subscription only from a status it still
     * has, returning the status it changed from, or empty when the current
     * status does not allow the change. The same compare-and-set loop as
     * SubscriptionService.transition, for the transitions dunning makes.
     *
     * This runs inside settlement's transaction, so it must not fail on a race
     * it can resolve: a miss re-reads the subscription and decides again, which
     * turns "someone paused it meanwhile" into a correct no-op instead of an
     * exception that would roll back a payment that really happened.
     */
    private Optional<SubscriptionStatus> transitionSubscription(
            String tenantId, SubscriptionEntity subscription, Set<SubscriptionStatus> allowedFrom,
            Predicate<SubscriptionStatus> applyFrom) {
        SubscriptionEntity current = subscription;
        for (int attempt = 0; attempt < MAX_TRANSITION_ATTEMPTS; attempt++) {
            SubscriptionStatus from = current.getStatus();
            if (!allowedFrom.contains(from)) {
                return Optional.empty();
            }
            if (applyFrom.test(from)) {
                return Optional.of(from);
            }
            current = subscriptions.findCurrentByTenantIdAndId(tenantId, subscription.getId())
                    .orElseThrow(() -> new IllegalStateException("Subscription " + subscription.getId() + " disappeared"));
        }
        // Three writers committing in the width of one settlement. A retried
        // webhook settles it; not a case worth more code than this.
        throw new OptimisticLockingFailureException(
                "Subscription " + subscription.getId() + " kept changing during dunning");
    }

    private void giveUp(InvoiceEntity invoice, DunningStateEntity state, String failureCode) {
        invoice.setStatus(InvoiceStatus.UNCOLLECTIBLE);
        invoices.save(invoice);
        audit.recordSystem(AuditEventType.INVOICE_UNCOLLECTIBLE, invoice.getId(), Map.of(
                "number", invoice.getNumber(),
                "attempts", state.getAttemptCount()));

        SubscriptionEntity subscription = invoice.getSubscription();
        String tenantId = invoice.getTenantId();
        transitionSubscription(tenantId, subscription, EnumSet.complementOf(EnumSet.of(SubscriptionStatus.CANCELED)),
                from -> subscriptions.cancelIfStatus(tenantId, subscription.getId(), from, Instant.now()))
                .ifPresent(from ->
                        // The same event type as an admin's cancellation; the
                        // actor and the reason are what tell the two apart.
                        audit.recordSystem(AuditEventType.SUBSCRIPTION_CANCELED, subscription.getId(), Map.of(
                                "from", from,
                                "reason", "DUNNING_EXHAUSTED",
                                "invoiceId", invoice.getId())));

        // The schedule has served its purpose; the invoice's status and its
        // failed payments are the lasting record.
        dunningStates.delete(state);
        log.warn("Gave up collecting invoice {} after {} attempts (last failure: {}); subscription {} canceled",
                invoice.getNumber(), state.getAttemptCount(), failureCode, subscription.getId());

        notificationService.enqueueSubscriptionCanceled(invoice);
    }
}
