package dev.lumjahaj.subscription.hub.customer.infra.jpa;

import dev.lumjahaj.subscription.hub.tenancy.infra.jpa.TenantScoped;
import jakarta.persistence.*;

@Entity
@Table(
        name = "customer",
        uniqueConstraints = @UniqueConstraint(name = "uk_customer_tenant_email", columnNames = {"tenant_id","email"})
)
public class CustomerEntity extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private java.util.UUID id;

    @Column(name = "external_id")
    private String externalId;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private String name;

    // A provider token (e.g. Stripe's "pm_..."), never card data. NULL means
    // this customer is never charged automatically - see V12.
    @Column(name = "default_payment_method", length = 64)
    private String defaultPaymentMethod;

    // Optimistic locking (V17). Hibernate adds "and version = ?" to every
    // UPDATE and increments it, so a write based on a stale read fails
    // instead of silently overwriting. Exposed to clients as the ETag.
    // A wrapper type: Spring Data treats a null version as "new", which is
    // why the column is NOT NULL DEFAULT 0 for existing rows.
    @Version
    @Column(nullable = false)
    private Long version;

    public java.util.UUID getId() {return id;}
    public void setId(java.util.UUID id) {this.id = id;}
    public String getExternalId() {return externalId;}
    public void setExternalId(String externalId) {this.externalId = externalId;}
    public String getEmail() {return email;}
    public void setEmail(String email) {this.email = email;}
    public String getName() {return name;}
    public void setName(String name) {this.name = name;}
    public String getDefaultPaymentMethod() {return defaultPaymentMethod;}
    public void setDefaultPaymentMethod(String defaultPaymentMethod) {this.defaultPaymentMethod = defaultPaymentMethod;}
    // No setter: the version belongs to Hibernate. Changing it on a managed
    // entity is not how a caller states which version it read - that check
    // is explicit, in CustomerService.update.
    public Long getVersion() {return version;}
}
