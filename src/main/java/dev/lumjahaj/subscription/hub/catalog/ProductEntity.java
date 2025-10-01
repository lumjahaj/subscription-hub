package dev.lumjahaj.subscription.hub.catalog;

import dev.lumjahaj.subscription.hub.tenancy.infra.TenantScoped;
import jakarta.persistence.*;

@Entity
@Table(
        name = "product",
        uniqueConstraints = @UniqueConstraint(name = "uk_product_tenant_code", columnNames = {"tenant_id","code"})
)
public class ProductEntity extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private java.util.UUID id;

    @Column(length = 64, nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column
    private String description;

    // getters/setters
    public java.util.UUID getId() {return id;}
    public void setId(java.util.UUID id) {this.id = id;}
    public String getCode() {return code;}
    public void setCode(String code) {this.code = code;}
    public String getName() {return name;}
    public void setName(String name) {this.name = name;}
    public String getDescription() {return description;}
    public void setDescription(String description) {this.description = description;}
}
