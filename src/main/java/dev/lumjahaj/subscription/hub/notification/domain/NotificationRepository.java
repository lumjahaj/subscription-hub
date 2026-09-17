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

    /**
     * Seconds since the oldest notification in this status was created, across
     * every active tenant, or 0 when there is none. For the outbox health
     * gauge only.
     *
     * The one deliberately cross-tenant read in the codebase. It returns a
     * single number and no row, so nothing of any tenant's leaks through it,
     * and inactive tenants are excluded because their rows are held in the
     * outbox on purpose - counting them would page someone about a suspension.
     */
    double oldestAgeSecondsAcrossActiveTenants(NotificationStatus status);
}
