package dev.lumjahaj.subscription.hub.dunning.app;

import dev.lumjahaj.subscription.hub.billing.domain.InvoiceRepository;
import dev.lumjahaj.subscription.hub.billing.domain.InvoiceStatus;
import dev.lumjahaj.subscription.hub.billing.infra.jpa.InvoiceEntity;
import dev.lumjahaj.subscription.hub.dunning.domain.DunningStateRepository;
import dev.lumjahaj.subscription.hub.dunning.infra.jpa.DunningStateEntity;
import dev.lumjahaj.subscription.hub.payment.domain.PaymentOutcomeListener;
import dev.lumjahaj.subscription.hub.payment.domain.PaymentRepository;
import dev.lumjahaj.subscription.hub.payment.domain.PaymentStatus;
import dev.lumjahaj.subscription.hub.subscription.domain.SubscriptionRepository;
import dev.lumjahaj.subscription.hub.subscription.domain.SubscriptionStatus;
import dev.lumjahaj.subscription.hub.subscription.infra.jpa.SubscriptionEntity;
import dev.lumjahaj.subscription.hub.tenancy.domain.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

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

    private final DunningStateRepository dunningStates;
    private final InvoiceRepository invoices;
    private final SubscriptionRepository subscriptions;
    private final PaymentRepository payments;
    private final DunningSchedule schedule;

    public DunningService(
            DunningStateRepository dunningStates,
            InvoiceRepository invoices,
            SubscriptionRepository subscriptions,
            PaymentRepository payments,
            DunningSchedule schedule
    ) {
        this.dunningStates = dunningStates;
        this.invoices = invoices;
        this.subscriptions = subscriptions;
        this.payments = payments;
        this.schedule = schedule;
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

        invoices.findByTenantIdAndId(tenantId, invoiceId).ifPresent(invoice -> {
            SubscriptionEntity subscription = invoice.getSubscription();
            if (subscription.getStatus() == SubscriptionStatus.PAST_DUE) {
                subscription.setStatus(SubscriptionStatus.ACTIVE);
                subscriptions.save(subscription);
                log.info("Subscription {} recovered from PAST_DUE after invoice {} was paid",
                        subscription.getId(), invoice.getNumber());
            }
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
        SubscriptionEntity subscription = invoice.getSubscription();
        SubscriptionStatus status = subscription.getStatus();
        // CANCELED stays canceled, and PAUSED is a deliberate customer
        // choice that non-payment shouldn't quietly overwrite.
        if (status != SubscriptionStatus.ACTIVE && status != SubscriptionStatus.TRIALING) {
            return;
        }
        subscription.setStatus(SubscriptionStatus.PAST_DUE);
        subscriptions.save(subscription);
        log.info("Subscription {} is PAST_DUE after a failed payment for invoice {}",
                subscription.getId(), invoice.getNumber());
    }

    private void giveUp(InvoiceEntity invoice, DunningStateEntity state, String failureCode) {
        invoice.setStatus(InvoiceStatus.UNCOLLECTIBLE);
        invoices.save(invoice);

        SubscriptionEntity subscription = invoice.getSubscription();
        if (subscription.getStatus() != SubscriptionStatus.CANCELED) {
            subscription.setStatus(SubscriptionStatus.CANCELED);
            subscription.setCanceledAt(Instant.now());
            subscriptions.save(subscription);
        }

        // The schedule has served its purpose; the invoice's status and its
        // failed payments are the lasting record.
        dunningStates.delete(state);
        log.warn("Gave up collecting invoice {} after {} attempts (last failure: {}); subscription {} canceled",
                invoice.getNumber(), state.getAttemptCount(), failureCode, subscription.getId());
    }
}
