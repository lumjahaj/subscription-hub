package dev.lumjahaj.subscription.hub.usage.infra.jpa;

import dev.lumjahaj.subscription.hub.subscription.infra.jpa.SubscriptionEntity;
import dev.lumjahaj.subscription.hub.tenancy.infra.jpa.TenantScoped;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "usage_counter",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_usage_counter_tenant_sub_meter_period",
                columnNames = {"tenant_id", "subscription_id", "meter_key", "period_start"})
)
public class UsageCounterEntity extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "subscription_id", nullable = false)
    private SubscriptionEntity subscription;

    @Column(name = "meter_key", length = 64, nullable = false)
    private String meterKey;

    @Column(name = "period_start", nullable = false)
    private Instant periodStart;

    @Column(name = "period_end", nullable = false)
    private Instant periodEnd;

    @Column(name = "amount", nullable = false, precision = 20, scale = 6)
    private BigDecimal amount;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public SubscriptionEntity getSubscription() { return subscription; }
    public void setSubscription(SubscriptionEntity subscription) { this.subscription = subscription; }
    public String getMeterKey() { return meterKey; }
    public void setMeterKey(String meterKey) { this.meterKey = meterKey; }
    public Instant getPeriodStart() { return periodStart; }
    public void setPeriodStart(Instant periodStart) { this.periodStart = periodStart; }
    public Instant getPeriodEnd() { return periodEnd; }
    public void setPeriodEnd(Instant periodEnd) { this.periodEnd = periodEnd; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
}
