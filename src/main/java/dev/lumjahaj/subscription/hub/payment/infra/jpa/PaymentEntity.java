package dev.lumjahaj.subscription.hub.payment.infra.jpa;

import dev.lumjahaj.subscription.hub.billing.infra.jpa.InvoiceEntity;
import dev.lumjahaj.subscription.hub.payment.domain.PaymentStatus;
import dev.lumjahaj.subscription.hub.tenancy.infra.jpa.TenantScoped;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

// The partial unique index ux_payment_invoice_in_flight_or_succeeded (V11)
// is deliberately not declared here: JPA has no way to express a WHERE
// clause on a unique constraint, and ddl-auto: validate doesn't check
// indexes, so the migration is its only definition.
@Entity
@Table(name = "payment", uniqueConstraints = {
        @UniqueConstraint(name = "uk_payment_tenant_idempotency_key", columnNames = {"tenant_id", "idempotency_key"})
})
public class PaymentEntity extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id", nullable = false)
    private InvoiceEntity invoice;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    // Native Postgres enum, same combo as InvoiceEntity.status (CLAUDE.md §5).
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false, columnDefinition = "payment_status")
    private PaymentStatus status;

    @Column(name = "provider", length = 32, nullable = false)
    private String provider;

    @Column(name = "payment_method", length = 64, nullable = false)
    private String paymentMethod;

    @Column(name = "provider_reference", length = 128)
    private String providerReference;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "idempotency_key", length = 128, nullable = false)
    private String idempotencyKey;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public InvoiceEntity getInvoice() { return invoice; }
    public void setInvoice(InvoiceEntity invoice) { this.invoice = invoice; }
    public long getAmountCents() { return amountCents; }
    public void setAmountCents(long amountCents) { this.amountCents = amountCents; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public PaymentStatus getStatus() { return status; }
    public void setStatus(PaymentStatus status) { this.status = status; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }
    public String getProviderReference() { return providerReference; }
    public void setProviderReference(String providerReference) { this.providerReference = providerReference; }
    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String failureCode) { this.failureCode = failureCode; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
}
