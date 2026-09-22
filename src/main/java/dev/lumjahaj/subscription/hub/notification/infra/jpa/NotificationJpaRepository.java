package dev.lumjahaj.subscription.hub.notification.infra.jpa;

import dev.lumjahaj.subscription.hub.notification.domain.NotificationStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationJpaRepository extends JpaRepository<NotificationEntity, UUID> {

    Optional<NotificationEntity> findByTenantIdAndId(String tenantId, UUID id);

    Optional<NotificationEntity> findByTenantIdAndDedupKey(String tenantId, String dedupKey);

    List<NotificationEntity> findByTenantIdAndStatusOrderByCreatedAtAsc(
            String tenantId, NotificationStatus status, Pageable pageable);

    /**
     * Native, and so outside Hibernate's @TenantId predicate - which here is
     * the point rather than the risk: a metrics scrape has no tenant, and this
     * aggregates over all of them. Contrast upsertAndIncrement and
     * allocateNextNumber, native for atomicity, which bind tenantId by hand.
     *
     * The aggregate itself lives in a SECURITY DEFINER function (V21) rather
     * than here, because being outside @TenantId is no longer enough: the
     * row-level security policies apply to every statement this connection
     * issues, so the query as written would read 0 across the board - a metric
     * that lies rather than fails. The function body runs as the table owner,
     * which the policies do not apply to, and EXECUTE on it is granted to
     * exactly one role. Deliberately the only such bypass in the codebase.
     *
     * status is varchar + CHECK (V14), not a Postgres enum, so it binds as a
     * plain string.
     */
    @Query(value = "select notification_outbox_oldest_age_seconds(:status)", nativeQuery = true)
    double oldestAgeSecondsAcrossActiveTenants(@Param("status") String status);
}
