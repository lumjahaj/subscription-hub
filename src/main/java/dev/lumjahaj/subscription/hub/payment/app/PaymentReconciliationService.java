package dev.lumjahaj.subscription.hub.payment.app;

import dev.lumjahaj.subscription.hub.payment.domain.PaymentEvent;
import dev.lumjahaj.subscription.hub.payment.domain.PaymentGateway;
import dev.lumjahaj.subscription.hub.payment.domain.PaymentLookup;
import dev.lumjahaj.subscription.hub.payment.domain.PaymentRepository;
import dev.lumjahaj.subscription.hub.payment.domain.PaymentRequest;
import dev.lumjahaj.subscription.hub.payment.infra.jpa.PaymentEntity;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Asks the provider what became of payments that have been PENDING too long,
 * and settles what it learns.
 *
 * It exists because settlement is asynchronous and delivery is not
 * guaranteed. A payment whose event never arrived — a webhook Stripe gave up
 * retrying, or a create call that timed out — stays PENDING, and
 * ux_payment_invoice_in_flight_or_succeeded then lets no other payment be
 * reserved for that invoice. DunningService.startAttempt sees that in-flight
 * payment and skips the invoice on every run from then on, so the invoice is
 * never collected, never written off, and the customer is never told. Nothing
 * else in the system notices, because nothing failed.
 *
 * Polling is the backstop, not the mechanism: the webhook still settles
 * virtually everything, and this only picks up what it dropped.
 *
 * Deliberately not @Transactional. The provider call is remote, and CLAUDE.md
 * §5 keeps those out of transactions — the same split PaymentService and
 * InvoicePdfService use. Settlement opens its own.
 */
@Service
public class PaymentReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationService.class);

    private final PaymentRepository payments;
    private final PaymentGateway gateway;
    private final PaymentSettlementService settlement;
    private final MeterRegistry registry;
    private final Duration minAge;
    private final int batchSize;

    public PaymentReconciliationService(
            PaymentRepository payments,
            PaymentGateway gateway,
            PaymentSettlementService settlement,
            MeterRegistry registry,
            @Value("${payment.reconciliation.min-age}") Duration minAge,
            @Value("${payment.reconciliation.batch-size}") int batchSize
    ) {
        this.payments = payments;
        this.gateway = gateway;
        this.settlement = settlement;
        this.registry = registry;
        this.minAge = minAge;
        this.batchSize = batchSize;
    }

    /**
     * The payments worth asking about: PENDING since longer ago than
     * {@code min-age}. Younger ones are simply mid-flight — with a real
     * provider a webhook is seconds away, and reconciling then would race the
     * normal path for no reason.
     */
    public List<PaymentEntity> findDue(String tenantId, Instant now) {
        return payments.findPendingOlderThan(tenantId, now.minus(minAge), batchSize);
    }

    /**
     * One payment, one provider call. Reads only columns on the payment row:
     * open-in-view is off, so the invoice association must not be touched
     * outside a transaction.
     *
     * A payment the provider cannot be reached for is left exactly as it is.
     * That is the whole discipline of this class — "unreachable" is not
     * "failed", and marking it failed would free the invoice's in-flight slot
     * for dunning to retry under a new idempotency key, charging a customer
     * whose card may already have been debited. It stays PENDING, the
     * payments.pending.oldest.age gauge keeps climbing, and a human is told.
     */
    public void reconcile(PaymentEntity payment) {
        PaymentLookup lookup = new PaymentLookup(
                new PaymentRequest(
                        payment.getTenantId(),
                        payment.getId(),
                        payment.getAmountCents(),
                        payment.getCurrency(),
                        payment.getPaymentMethod()),
                payment.getProviderReference());

        Optional<PaymentEvent> outcome = gateway.reconcile(lookup);
        if (outcome.isEmpty()) {
            log.debug("Payment {} is still in progress at {}", payment.getId(), payment.getProvider());
            return;
        }

        PaymentEvent event = outcome.get();
        log.info("Reconciled payment {}: {} says {}", payment.getId(), event.provider(), event.outcome());
        settlement.handle(event);
        countReconciled(event);
    }

    /**
     * payments.reconciled (outcome). payments.settled already counts every
     * settlement, including these; what it cannot say is how many only
     * happened because we went and asked. That number is the health of the
     * webhook path, so it earns a counter of its own rather than a tag on an
     * existing series.
     *
     * Not deferred through AfterCommit: settlement has already committed by
     * the time this runs, since this class holds no transaction.
     */
    private void countReconciled(PaymentEvent event) {
        Counter.builder("payments.reconciled")
                .description("Payments settled by reconciliation rather than a provider event")
                .tag("outcome", event.outcome().name())
                .tag("provider", event.provider())
                .register(registry)
                .increment();
    }
}
