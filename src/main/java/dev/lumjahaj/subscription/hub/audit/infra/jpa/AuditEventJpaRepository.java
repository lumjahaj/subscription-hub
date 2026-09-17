package dev.lumjahaj.subscription.hub.audit.infra.jpa;

import dev.lumjahaj.subscription.hub.audit.domain.AuditEntityType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AuditEventJpaRepository extends JpaRepository<AuditEventEntity, UUID> {
    Page<AuditEventEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId, Pageable pageable);
    Page<AuditEventEntity> findByTenantIdAndEntityTypeAndEntityIdOrderByCreatedAtDesc(
            String tenantId, AuditEntityType entityType, String entityId, Pageable pageable);
}
