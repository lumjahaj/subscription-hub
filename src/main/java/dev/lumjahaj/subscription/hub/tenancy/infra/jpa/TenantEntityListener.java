package dev.lumjahaj.subscription.hub.tenancy.infra.jpa;

import dev.lumjahaj.subscription.hub.tenancy.domain.TenantContext;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

import java.time.Instant;

public class TenantEntityListener {
    @PrePersist
    public void prePersist(TenantScoped entity) {
        if (entity.getTenantId() == null) {
            String tid = TenantContext.getTenantId();
            if (tid == null) throw new IllegalStateException("No tenant in context for persist");
            entity.setTenantId(tid);
        }
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
    }

    @PreUpdate
    public void preUpdate(TenantScoped entity) {
        entity.setUpdatedAt(Instant.now());
    }
}
