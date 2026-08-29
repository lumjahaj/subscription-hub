package dev.lumjahaj.subscription.hub.billing.infra.jpa;

import dev.lumjahaj.subscription.hub.billing.domain.InvoiceLineKind;
import dev.lumjahaj.subscription.hub.tenancy.infra.TenantScoped;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "invoice_line")
public class InvoiceLineEntity extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id", nullable = false)
    private InvoiceEntity invoice;

    // Deliberately NOT the NAMED_ENUM combo InvoiceEntity.status uses:
    // invoice_line.kind is varchar(16), not a Postgres enum type, so
    // @JdbcTypeCode(SqlTypes.NAMED_ENUM) here would bind a nonexistent
    // type and fail at startup. Plain @Enumerated(EnumType.STRING) is
    // correct for this column.
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 16, nullable = false)
    private InvoiceLineKind kind;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "quantity", nullable = false, precision = 20, scale = 6)
    private BigDecimal quantity;

    @Column(name = "unit_amount_cents", nullable = false)
    private long unitAmountCents;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public InvoiceEntity getInvoice() { return invoice; }
    public void setInvoice(InvoiceEntity invoice) { this.invoice = invoice; }
    public InvoiceLineKind getKind() { return kind; }
    public void setKind(InvoiceLineKind kind) { this.kind = kind; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
    public long getUnitAmountCents() { return unitAmountCents; }
    public void setUnitAmountCents(long unitAmountCents) { this.unitAmountCents = unitAmountCents; }
    public long getAmountCents() { return amountCents; }
    public void setAmountCents(long amountCents) { this.amountCents = amountCents; }
}
