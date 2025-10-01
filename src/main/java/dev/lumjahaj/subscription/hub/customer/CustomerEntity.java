package dev.lumjahaj.subscription.hub.customer;

import dev.lumjahaj.subscription.hub.tenancy.infra.TenantScoped;
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

    // getters/setters
    public java.util.UUID getId() {return id;}
    public void setId(java.util.UUID id) {this.id = id;}
    public String getExternalId() {return externalId;}
    public void setExternalId(String externalId) {this.externalId = externalId;}
    public String getEmail() {return email;}
    public void setEmail(String email) {this.email = email;}
    public String getName() {return name;}
    public void setName(String name) {this.name = name;}
}
