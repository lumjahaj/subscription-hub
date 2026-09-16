package dev.lumjahaj.subscription.hub.notification.infra.jpa;

import dev.lumjahaj.subscription.hub.notification.domain.NotificationStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationJpaRepository extends JpaRepository<NotificationEntity, UUID> {

    Optional<NotificationEntity> findByTenantIdAndId(String tenantId, UUID id);

    Optional<NotificationEntity> findByTenantIdAndDedupKey(String tenantId, String dedupKey);

    List<NotificationEntity> findByTenantIdAndStatusOrderByCreatedAtAsc(
            String tenantId, NotificationStatus status, Pageable pageable);
}
