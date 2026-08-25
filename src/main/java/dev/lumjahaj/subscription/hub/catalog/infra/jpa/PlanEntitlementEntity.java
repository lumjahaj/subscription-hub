package dev.lumjahaj.subscription.hub.catalog.infra.jpa;

import dev.lumjahaj.subscription.hub.tenancy.infra.TenantScoped;
import jakarta.persistence.*;

@Entity
@Table(
        name = "plan_entitlement",
        uniqueConstraints = @UniqueConstraint(name = "uk_plan_ent_tenant_plan_key", columnNames = {"tenant_id","plan_id","key"})
)
public class PlanEntitlementEntity extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private java.util.UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    private PlanEntity plan;

    @Column(name = "key", length = 64, nullable = false)
    private String key;

    @Column(name = "value_json", columnDefinition = "jsonb", nullable = false)
    private String valueJson; // keep as String; parse to/from JSON in service/DTO

    // getters/setters
    public java.util.UUID getId() {return id;}
    public void setId(java.util.UUID id) {this.id = id;}
    public PlanEntity getPlan() {return plan;}
    public void setPlan(PlanEntity plan) {this.plan = plan;}
    public String getKey() {return key;}
    public void setKey(String key) {this.key = key;}
    public String getValueJson() {return valueJson;}
    public void setValueJson(String valueJson) {this.valueJson = valueJson;}
}
