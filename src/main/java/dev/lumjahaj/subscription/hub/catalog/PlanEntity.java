package dev.lumjahaj.subscription.hub.catalog;

import dev.lumjahaj.subscription.hub.tenancy.infra.TenantScoped;
import jakarta.persistence.*;

@Entity
@Table(
        name = "plan",
        uniqueConstraints = @UniqueConstraint(name = "uk_plan_tenant_code", columnNames = {"tenant_id","code"})
)
public class PlanEntity extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private java.util.UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private ProductEntity product;

    @Column(length = 64, nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(name = "interval", nullable = false, length = 16)
    private String interval; // MONTH|YEAR (string-mapped for simplicity)

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "EUR";

    @Column(name = "trial_days", nullable = false)
    private int trialDays = 0;

    // getters/setters
    public java.util.UUID getId() {return id;}
    public void setId(java.util.UUID id) {this.id = id;}
    public ProductEntity getProduct() {return product;}
    public void setProduct(ProductEntity product) {this.product = product;}
    public String getCode() {return code;}
    public void setCode(String code) {this.code = code;}
    public String getName() {return name;}
    public void setName(String name) {this.name = name;}
    public String getInterval() {return interval;}
    public void setInterval(String interval) {this.interval = interval;}
    public long getAmountCents() {return amountCents;}
    public void setAmountCents(long amountCents) {this.amountCents = amountCents;}
    public String getCurrency() {return currency;}
    public void setCurrency(String currency) {this.currency = currency;}
    public int getTrialDays() {return trialDays;}
    public void setTrialDays(int trialDays) {this.trialDays = trialDays;}
}
