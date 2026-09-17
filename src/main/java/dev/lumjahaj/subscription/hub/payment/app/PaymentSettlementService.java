package dev.lumjahaj.subscription.hub.payment.app;

import dev.lumjahaj.subscription.hub.audit.app.AuditService;
import dev.lumjahaj.subscription.hub.audit.domain.AuditEventType;
import dev.lumjahaj.subscription.hub.billing.domain.InvoiceRepository;
import dev.lumjahaj.subscription.hub.billing.domain.InvoiceStatus;
import dev.lumjahaj.subscription.hub.billing.infra.jpa.InvoiceEntity;
import dev.lumjahaj.subscription.hub.common.metrics.AfterCommit;
import dev.lumjahaj.subscription.hub.payment.domain.PaymentEvent;
import dev.lumjahaj.subscription.hub.payment.domain.PaymentEventHandler;
import dev.lumjahaj.subscription.hub.payment.domain.PaymentOutcomeListener;
import dev.lumjahaj.subscription.hub.payment.domain.PaymentRepository;
import dev.lumjahaj.subscription.hub.payment.domain.PaymentStatus;
import dev.lumjahaj.subscription.hub.payment.infra.jpa.PaymentEntity;
import dev.lumjahaj.subscription.hub.tenancy.domain.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The only code that settles money: a payment becomes SUCCEEDED or FAILED,
 * and an invoice becomes PAID, here and nowhere else. Every provider, fake
 * or real, reaches this through a {@link PaymentEvent}, so there is one
 * settlement path to test rather than one per provider.
 *
 * Idempotent by state rather than by remembering event ids: only a PENDING
 * payment can settle, and it settles exactly once, so a redelivered,
 * duplicated or late event finds a settled payment and changes nothing.
 * That holds because each payment is one provider payment with one
 * terminal outcome. Tracking processed event ids becomes necessary only
 * for events that aren't naturally idempotent (refunds, disputes), which
 * don't exist yet.
 */
@Service
public class PaymentSettlementService implements PaymentEventHandler {

    private static final Logger log = LoggerFactory.getLogger(PaymentSettlementService.class);

    private final PaymentRepository payments;
    private final InvoiceRepository invoices;
    private final List<PaymentOutcomeListener> listeners;
    private final AuditService audit;
    private final MeterRegistry registry;

    /**
     * The listener list is injected rather than a single collaborator, and
     * may be empty: the payment module works on its own, and dunning is one
     * optional reaction to an outcome rather than part of settling it.
     */
    public PaymentSettlementService(
            PaymentRepository payments,
            InvoiceRepository invoices,
            List<PaymentOutcomeListener> listeners,
            AuditService audit,
            MeterRegistry registry
    ) {
        this.payments = payments;
        this.invoices = invoices;
        this.listeners = listeners;
        this.audit = audit;
        this.registry = registry;
    }

    /**
     * The caller must already have established the event's tenant in
     * TenantContext (a request, or TenantContext.runAs). Refusing a
     * mismatch rather than switching tenant here, because Hibernate's
     * @TenantId predicate is bound to the tenant the session opened with,
     * and TenantContext.runAs clears rather than restores — switching
     * inside a request would silently unscope the rest of it.
     */
    @Override
    @Transactional
    public void handle(PaymentEvent event) {
        String tenantId = TenantContext.getTenantId();
        if (!event.tenantId().equals(tenantId)) {
            throw new IllegalStateException("Payment event " + event.eventId()
                    + " is for tenant " + event.tenantId() + " but the current tenant is " + tenantId);
        }

        PaymentEntity payment = payments.findByTenantIdAndId(tenantId, event.paymentId()).orElse(null);
        if (payment == null) {
            // Not an error for the provider to retry: nothing will make
            // this payment appear later.
            log.warn("Ignoring payment event {} for unknown payment {}", event.eventId(), event.paymentId());
            return;
        }
        if (!payment.getProvider().equals(event.provider())) {
            log.warn("Ignoring payment event {} from provider {} for a payment handled by {}",
                    event.eventId(), event.provider(), payment.getProvider());
            return;
        }
        if (payment.getStatus() != PaymentStatus.PENDING) {
            log.info("Ignoring payment event {}: payment {} is already {}",
                    event.eventId(), payment.getId(), payment.getStatus());
            return;
        }
        if (payment.getProviderReference() != null
                && !payment.getProviderReference().equals(event.providerReference())) {
            log.warn("Ignoring payment event {}: reference {} does not match payment {}'s reference {}",
                    event.eventId(), event.providerReference(), payment.getId(), payment.getProviderReference());
            return;
        }

        // The event can arrive before PaymentService has stored the
        // reference returned by the create call, so it may be set here first.
        payment.setProviderReference(event.providerReference());

        switch (event.outcome()) {
            case SUCCEEDED -> {
                payment.setStatus(PaymentStatus.SUCCEEDED);
                markInvoicePaid(payment.getInvoice(), payment);
            }
            case FAILED -> {
                payment.setStatus(PaymentStatus.FAILED);
                payment.setFailureCode(event.failureCode());
            }
        }
        payments.save(payment);
        recordOutcome(payment);
        countSettlement(payment);

        // After the payment is saved, and inside the same transaction: a
        // listener reacting to an outcome that then rolls back would leave a
        // subscription past due for a payment that never failed.
        notifyListeners(payment, event);
    }

    /**
     * payments.settled (outcome, provider). audit.events already counts
     * PAYMENT_SUCCEEDED and PAYMENT_FAILED; this adds the provider, so a
     * spike in failures can be told apart from one provider misbehaving.
     * Provider is an adapter name (fake, stripe), never a payment method.
     */
    private void countSettlement(PaymentEntity payment) {
        String outcome = payment.getStatus().name();
        String provider = payment.getProvider();
        AfterCommit.run(() -> Counter.builder("payments.settled")
                .description("Payments settled by a provider event, once committed")
                .tag("outcome", outcome)
                .tag("provider", provider)
                .register(registry)
                .increment());
    }

    /** SYSTEM regardless of provider; see AuditService.recordSystem. */
    private void recordOutcome(PaymentEntity payment) {
        Map<String, Object> data = new HashMap<>();
        data.put("invoiceId", payment.getInvoice().getId());
        data.put("amountCents", payment.getAmountCents());
        data.put("currency", payment.getCurrency());
        data.put("provider", payment.getProvider());
        if (payment.getStatus() == PaymentStatus.FAILED && payment.getFailureCode() != null) {
            data.put("failureCode", payment.getFailureCode());
        }
        AuditEventType type = payment.getStatus() == PaymentStatus.SUCCEEDED
                ? AuditEventType.PAYMENT_SUCCEEDED
                : AuditEventType.PAYMENT_FAILED;
        audit.recordSystem(type, payment.getId(), data);
    }

    private void notifyListeners(PaymentEntity payment, PaymentEvent event) {
        UUID invoiceId = payment.getInvoice().getId();
        for (PaymentOutcomeListener listener : listeners) {
            switch (event.outcome()) {
                case SUCCEEDED -> listener.onPaymentSucceeded(invoiceId);
                case FAILED -> listener.onPaymentFailed(invoiceId, event.failureCode());
            }
        }
    }

    private void markInvoicePaid(InvoiceEntity invoice, PaymentEntity payment) {
        if (invoice.getStatus() != InvoiceStatus.OPEN) {
            // The in-flight index makes this unreachable through the API.
            // If it ever happens, money has still moved: record the payment
            // as succeeded and leave the invoice alone for a human to refund.
            log.error("Payment {} succeeded for invoice {} which is {}, not OPEN - needs manual review",
                    payment.getId(), invoice.getNumber(), invoice.getStatus());
            return;
        }
        invoice.setStatus(InvoiceStatus.PAID);
        invoice.setPaidAt(Instant.now());
        invoices.save(invoice);
        audit.recordSystem(AuditEventType.INVOICE_PAID, invoice.getId(), Map.of(
                "number", invoice.getNumber(),
                "paymentId", payment.getId()));
    }
}
