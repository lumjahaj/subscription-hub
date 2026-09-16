package dev.lumjahaj.subscription.hub.dunning.infra.jpa;

import dev.lumjahaj.subscription.hub.billing.infra.jpa.InvoiceEntity;
import dev.lumjahaj.subscription.hub.tenancy.infra.jpa.TenantScoped;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "dunning_state", uniqueConstraints = {
        @UniqueConstraint(name = "uk_dunning_state_tenant_invoice", columnNames = {"tenant_id", "invoice_id"})
})
public class DunningStateEntity extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id", nullable = false)
    private InvoiceEntity invoice;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_failure_code", length = 64)
    private String lastFailureCode;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public InvoiceEntity getInvoice() { return invoice; }
    public void setInvoice(InvoiceEntity invoice) { this.invoice = invoice; }
    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
    public String getLastFailureCode() { return lastFailureCode; }
    public void setLastFailureCode(String lastFailureCode) { this.lastFailureCode = lastFailureCode; }
}
