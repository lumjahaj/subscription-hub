package dev.lumjahaj.subscription.hub.notification.infra.jpa;

import dev.lumjahaj.subscription.hub.notification.domain.NotificationRepository;
import dev.lumjahaj.subscription.hub.notification.domain.NotificationStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class NotificationRepositoryImpl implements NotificationRepository {

    private final NotificationJpaRepository jpaRepository;

    public NotificationRepositoryImpl(NotificationJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public NotificationEntity save(NotificationEntity notification) {
        return jpaRepository.save(notification);
    }

    @Override
    public Optional<NotificationEntity> findByTenantIdAndId(String tenantId, UUID id) {
        return jpaRepository.findByTenantIdAndId(tenantId, id);
    }

    @Override
    public Optional<NotificationEntity> findByTenantIdAndDedupKey(String tenantId, String dedupKey) {
        return jpaRepository.findByTenantIdAndDedupKey(tenantId, dedupKey);
    }

    @Override
    public List<NotificationEntity> findByTenantIdAndStatus(
            String tenantId, NotificationStatus status, Pageable pageable) {
        return jpaRepository.findByTenantIdAndStatusOrderByCreatedAtAsc(tenantId, status, pageable);
    }

    @Override
    public double oldestAgeSecondsAcrossActiveTenants(NotificationStatus status) {
        return jpaRepository.oldestAgeSecondsAcrossActiveTenants(status.name());
    }
}
