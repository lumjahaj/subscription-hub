package dev.lumjahaj.subscription.hub.billing.infra.jpa;

import dev.lumjahaj.subscription.hub.billing.domain.InvoiceStatus;
import dev.lumjahaj.subscription.hub.customer.infra.jpa.CustomerEntity;
import dev.lumjahaj.subscription.hub.subscription.infra.jpa.SubscriptionEntity;
import dev.lumjahaj.subscription.hub.tenancy.infra.TenantScoped;
import jakarta.persistence.*;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "invoice", uniqueConstraints = {
        @UniqueConstraint(name = "uk_invoice_tenant_number", columnNames = {"tenant_id", "number"}),
        @UniqueConstraint(name = "uk_invoice_tenant_sub_period", columnNames = {"tenant_id", "subscription_id", "period_start"})
})
public class InvoiceEntity extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "subscription_id", nullable = false)
    private SubscriptionEntity subscription;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private CustomerEntity customer;

    @Column(name = "number", length = 32, nullable = false)
    private String number;

    // NAMED_ENUM tells Hibernate to bind this as the native Postgres
    // invoice_status type, not varchar — same category of bug as
    // SubscriptionEntity.status and PlanEntity.intervalUnit.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false, columnDefinition = "invoice_status")
    private InvoiceStatus status;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "total_cents", nullable = false)
    private long totalCents;

    @Column(name = "period_start", nullable = false)
    private Instant periodStart;

    @Column(name = "period_end", nullable = false)
    private Instant periodEnd;

    @Column(name = "issued_at")
    private Instant issuedAt;

    @Column(name = "due_at")
    private Instant dueAt;

    // Nullable by design: an invoice exists before its PDF does, because
    // PDF generation is decoupled from invoice generation so a storage
    // outage can't fail a billing run. NULL means "not generated yet".
    // Holds an object key ("acme/INV-000001.pdf"), not a URL — the column
    // was named pdf_url in V1 and renamed in V7 to stop it lying.
    @Column(name = "pdf_object_key")
    private String pdfObjectKey;

    // The codebase's first @OneToMany. An invoice and its lines are one
    // aggregate — written in a single transaction, a line has no lifetime
    // apart from its invoice, and totalCents is only correct if computed
    // from exactly the lines that were saved. Cascading makes that
    // atomicity structural instead of a rule InvoiceService has to
    // remember, and it means there is no separate InvoiceLineRepository
    // trio: nothing ever queries a line independently of its invoice.
    //
    // @OrderBy sorts BASE before USAGE alphabetically, for free.
    // @BatchSize answers the paged-list N+1: without it, listing a page
    // of invoices either lazy-loads each invoice's lines one at a time,
    // or a fetch-join pulls all of them in one query but then silently
    // paginates in memory (Hibernate's HHH90003004 warning) because a
    // collection fetch can't be combined with firstResult/maxResults.
    // @BatchSize loads all lines for a page of invoices in one extra
    // "IN (...)" query instead.
    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("kind, description")
    @BatchSize(size = 32)
    private List<InvoiceLineEntity> lines = new ArrayList<>();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public SubscriptionEntity getSubscription() { return subscription; }
    public void setSubscription(SubscriptionEntity subscription) { this.subscription = subscription; }
    public CustomerEntity getCustomer() { return customer; }
    public void setCustomer(CustomerEntity customer) { this.customer = customer; }
    public String getNumber() { return number; }
    public void setNumber(String number) { this.number = number; }
    public InvoiceStatus getStatus() { return status; }
    public void setStatus(InvoiceStatus status) { this.status = status; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public long getTotalCents() { return totalCents; }
    public void setTotalCents(long totalCents) { this.totalCents = totalCents; }
    public Instant getPeriodStart() { return periodStart; }
    public void setPeriodStart(Instant periodStart) { this.periodStart = periodStart; }
    public Instant getPeriodEnd() { return periodEnd; }
    public void setPeriodEnd(Instant periodEnd) { this.periodEnd = periodEnd; }
    public Instant getIssuedAt() { return issuedAt; }
    public void setIssuedAt(Instant issuedAt) { this.issuedAt = issuedAt; }
    public Instant getDueAt() { return dueAt; }
    public void setDueAt(Instant dueAt) { this.dueAt = dueAt; }
    public String getPdfObjectKey() { return pdfObjectKey; }
    public void setPdfObjectKey(String pdfObjectKey) { this.pdfObjectKey = pdfObjectKey; }
    public List<InvoiceLineEntity> getLines() { return lines; }

    public void addLine(InvoiceLineEntity line) {
        line.setInvoice(this);
        lines.add(line);
    }
}
