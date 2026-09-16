package dev.lumjahaj.subscription.hub.payment.app;

import dev.lumjahaj.subscription.hub.billing.domain.InvoiceRepository;
import dev.lumjahaj.subscription.hub.billing.domain.InvoiceStatus;
import dev.lumjahaj.subscription.hub.billing.infra.jpa.InvoiceEntity;
import dev.lumjahaj.subscription.hub.common.api.ResourceNotFoundException;
import dev.lumjahaj.subscription.hub.payment.domain.PaymentGateway;
import dev.lumjahaj.subscription.hub.payment.domain.PaymentGatewayException;
import dev.lumjahaj.subscription.hub.payment.domain.PaymentRepository;
import dev.lumjahaj.subscription.hub.payment.domain.PaymentRequest;
import dev.lumjahaj.subscription.hub.payment.domain.PaymentStatus;
import dev.lumjahaj.subscription.hub.payment.infra.jpa.PaymentEntity;
import dev.lumjahaj.subscription.hub.tenancy.domain.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Starts payments. Never settles them — that is PaymentSettlementService,
 * driven by provider events.
 *
 * Deliberately not @Transactional. A payment is three steps with the
 * provider call in the middle, and CLAUDE.md §5 keeps remote calls out of
 * transactions: holding one open across the call would pin a database
 * connection for as long as the provider takes, and a rollback can't undo
 * a charge anyway. The two short transactions are explicit
 * TransactionTemplate blocks so the boundaries are visible where they
 * happen, rather than depending on which method calls go through a proxy.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository payments;
    private final InvoiceRepository invoices;
    private final PaymentGateway gateway;
    private final TransactionTemplate transaction;

    public PaymentService(
            PaymentRepository payments,
            InvoiceRepository invoices,
            PaymentGateway gateway,
            TransactionTemplate transaction
    ) {
        this.payments = payments;
        this.invoices = invoices;
        this.gateway = gateway;
        this.transaction = transaction;
    }

    /**
     * 1. Reserve: in a transaction, check the invoice is OPEN and insert a
     *    PENDING payment. The amount and currency are copied from the
     *    invoice, never accepted from the caller.
     * 2. Submit: outside any transaction, ask the provider to collect it.
     * 3. Record: in a second transaction, store the provider's reference.
     *
     * The outcome is not decided here. It arrives as a provider event, which
     * for the fake gateway happens during step 2 and for a real provider
     * happens whenever its webhook is delivered.
     */
    public PaymentAttempt pay(UUID invoiceId, String paymentMethod, String idempotencyKey) {
        String tenantId = TenantContext.getTenantId();

        Optional<PaymentEntity> earlier = payments.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey);
        if (earlier.isPresent()) {
            return replay(tenantId, earlier.get(), invoiceId, paymentMethod);
        }

        PaymentEntity payment;
        try {
            payment = transaction.execute(status ->
                    reserve(tenantId, invoiceId, paymentMethod, idempotencyKey));
        } catch (DataIntegrityViolationException conflict) {
            // The reserve transaction inserts one row, so it can only have
            // lost one of two races: the same Idempotency-Key sent
            // concurrently (replay it), or another payment for this invoice
            // reaching the in-flight index first.
            Optional<PaymentEntity> sameKey = payments.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey);
            if (sameKey.isPresent()) {
                return replay(tenantId, sameKey.get(), invoiceId, paymentMethod);
            }
            throw new PaymentInProgressException(invoiceId);
        }

        submit(tenantId, payment);
        return new PaymentAttempt(reload(tenantId, payment.getId()), true);
    }

    public PaymentEntity getById(UUID paymentId) {
        String tenantId = TenantContext.getTenantId();
        return payments.findByTenantIdAndId(tenantId, paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId.toString()));
    }

    public List<PaymentEntity> listForInvoice(UUID invoiceId) {
        String tenantId = TenantContext.getTenantId();
        findOwnedInvoice(tenantId, invoiceId);
        return payments.findByTenantIdAndInvoiceId(tenantId, invoiceId);
    }

    private PaymentEntity reserve(String tenantId, UUID invoiceId, String paymentMethod, String idempotencyKey) {
        InvoiceEntity invoice = findOwnedInvoice(tenantId, invoiceId);
        if (invoice.getStatus() != InvoiceStatus.OPEN) {
            throw new InvoiceNotPayableException(invoice.getNumber(), invoice.getStatus());
        }

        PaymentEntity payment = new PaymentEntity();
        payment.setTenantId(tenantId);
        payment.setInvoice(invoice);
        payment.setAmountCents(invoice.getTotalCents());
        payment.setCurrency(invoice.getCurrency());
        payment.setStatus(PaymentStatus.PENDING);
        payment.setProvider(gateway.provider());
        payment.setPaymentMethod(paymentMethod);
        payment.setIdempotencyKey(idempotencyKey);
        return payments.saveAndFlush(payment);
    }

    /**
     * Same key, same request: return what happened the first time. The one
     * exception is a payment the provider never acknowledged (the first
     * attempt hit PaymentProviderUnavailableException): that one is
     * submitted again, which is safe because the provider receives the same
     * idempotency key — our payment id — both times.
     */
    private PaymentAttempt replay(String tenantId, PaymentEntity earlier, UUID invoiceId, String paymentMethod) {
        if (!earlier.getInvoice().getId().equals(invoiceId) || !earlier.getPaymentMethod().equals(paymentMethod)) {
            throw new IdempotencyKeyReusedException();
        }
        if (earlier.getStatus() == PaymentStatus.PENDING && earlier.getProviderReference() == null) {
            submit(tenantId, earlier);
            return new PaymentAttempt(reload(tenantId, earlier.getId()), false);
        }
        return new PaymentAttempt(earlier, false);
    }

    private void submit(String tenantId, PaymentEntity payment) {
        UUID paymentId = payment.getId();
        String reference;
        try {
            reference = gateway.createPayment(new PaymentRequest(
                    tenantId, paymentId, payment.getAmountCents(), payment.getCurrency(), payment.getPaymentMethod()));
        } catch (PaymentGatewayException ex) {
            log.warn("Payment provider unavailable for payment {}; left PENDING", paymentId, ex);
            throw new PaymentProviderUnavailableException();
        }

        transaction.executeWithoutResult(status -> {
            PaymentEntity current = reload(tenantId, paymentId);
            // Only fills a gap: a fast event may already have set it.
            if (current.getProviderReference() == null) {
                current.setProviderReference(reference);
                payments.save(current);
            }
        });
    }

    private PaymentEntity reload(String tenantId, UUID paymentId) {
        return payments.findByTenantIdAndId(tenantId, paymentId)
                .orElseThrow(() -> new IllegalStateException("Payment " + paymentId + " disappeared"));
    }

    private InvoiceEntity findOwnedInvoice(String tenantId, UUID invoiceId) {
        return invoices.findByTenantIdAndId(tenantId, invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice", invoiceId.toString()));
    }
}
