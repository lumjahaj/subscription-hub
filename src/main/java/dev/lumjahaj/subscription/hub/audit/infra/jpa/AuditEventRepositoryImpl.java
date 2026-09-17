package dev.lumjahaj.subscription.hub.audit.infra.jpa;

import dev.lumjahaj.subscription.hub.audit.domain.AuditEntityType;
import dev.lumjahaj.subscription.hub.audit.domain.AuditEventRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
public class AuditEventRepositoryImpl implements AuditEventRepository {

    private final AuditEventJpaRepository jpaRepository;

    public AuditEventRepositoryImpl(AuditEventJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public AuditEventEntity append(AuditEventEntity event) {
        return jpaRepository.save(event);
    }

    @Override
    public Page<AuditEventEntity> findByTenantId(String tenantId, Pageable pageable) {
        return jpaRepository.findByTenantIdOrderByCreatedAtDesc(tenantId, pageable);
    }

    @Override
    public Page<AuditEventEntity> findByTenantIdAndEntity(
            String tenantId, AuditEntityType entityType, String entityId, Pageable pageable) {
        return jpaRepository.findByTenantIdAndEntityTypeAndEntityIdOrderByCreatedAtDesc(
                tenantId, entityType, entityId, pageable);
    }
}
