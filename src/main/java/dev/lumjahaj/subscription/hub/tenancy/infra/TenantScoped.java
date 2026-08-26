package dev.lumjahaj.subscription.hub.tenancy.infra;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.annotations.TenantId;

import java.time.Instant;

@MappedSuperclass
@EntityListeners(TenantEntityListener.class)
public abstract class TenantScoped {

    // @TenantId makes Hibernate append "tenant_id = :resolvedTenant" to
    // every query it builds for a TenantScoped entity, using
    // TenantIdentifierResolver — a safety net on top of the explicit
    // findByTenantId... methods, in case one is ever missed.
    @TenantId
    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
