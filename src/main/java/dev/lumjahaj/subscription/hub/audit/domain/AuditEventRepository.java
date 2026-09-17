package dev.lumjahaj.subscription.hub.audit.domain;

import dev.lumjahaj.subscription.hub.audit.infra.jpa.AuditEventEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Append-only: there is no update and no delete, and there should never be.
 * An audit log that the application can rewrite records what the
 * application currently claims, not what happened.
 *
 * Every read takes the tenant explicitly, and that is the whole of the
 * isolation for this table - see AuditEventEntity for why it has no
 * {@code @TenantId} backstop.
 */
public interface AuditEventRepository {
    AuditEventEntity append(AuditEventEntity event);
    Page<AuditEventEntity> findByTenantId(String tenantId, Pageable pageable);
    Page<AuditEventEntity> findByTenantIdAndEntity(
            String tenantId, AuditEntityType entityType, String entityId, Pageable pageable);
}
