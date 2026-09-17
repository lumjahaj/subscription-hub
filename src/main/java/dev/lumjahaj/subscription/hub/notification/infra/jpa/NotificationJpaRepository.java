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
     * Age is computed with the database's clock, the same clock that wrote
     * created_at, so application/database clock skew cannot distort it.
     * status is varchar + CHECK (V14), not a Postgres enum, so it binds as a
     * plain string.
     */
    @Query(value = """
            select cast(coalesce(extract(epoch from (now() - min(n.created_at))), 0) as double precision)
              from notification n
              join tenant t on t.id = n.tenant_id
             where n.status = :status
               and t.active
            """, nativeQuery = true)
    double oldestAgeSecondsAcrossActiveTenants(@Param("status") String status);
}
