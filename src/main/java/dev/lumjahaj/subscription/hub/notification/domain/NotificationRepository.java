package dev.lumjahaj.subscription.hub.notification.domain;

import dev.lumjahaj.subscription.hub.notification.infra.jpa.NotificationEntity;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository {

    NotificationEntity save(NotificationEntity notification);

    Optional<NotificationEntity> findByTenantIdAndId(String tenantId, UUID id);

    Optional<NotificationEntity> findByTenantIdAndDedupKey(String tenantId, String dedupKey);

    List<NotificationEntity> findByTenantIdAndStatus(String tenantId, NotificationStatus status, Pageable pageable);
}
