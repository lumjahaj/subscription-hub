package dev.lumjahaj.subscription.hub.subscription.infra.jpa;

import dev.lumjahaj.subscription.hub.subscription.domain.SubscriptionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubscriptionJpaRepository extends JpaRepository<SubscriptionEntity, UUID> {

    // plan is fetched on the finders behind API responses, because
    // SubscriptionMapper reads planCode after the transaction has ended
    // (see PlanJpaRepository). customer is not: the mapper reads only its id,
    // which a Hibernate proxy answers without loading anything.
    @EntityGraph(attributePaths = "plan")
    Page<SubscriptionEntity> findByTenantId(String tenantId, Pageable pageable);

    @EntityGraph(attributePaths = "plan")
    Page<SubscriptionEntity> findByTenantIdAndCustomerId(String tenantId, UUID customerId, Pageable pageable);

    @EntityGraph(attributePaths = "plan")
    Optional<SubscriptionEntity> findByTenantIdAndId(String tenantId, UUID id);

    // BillingCycleJob reads only the ids and reloads each one in its own
    // transaction, so this stays a plain query.
    List<SubscriptionEntity> findByTenantIdAndStatusInAndNextRenewalLessThanEqual(
            String tenantId, Collection<SubscriptionStatus> statuses, Instant cutoff);

    // The compare-and-set updates behind SubscriptionRepository's state
    // commands. JPQL rather than native, so status binds through the entity's
    // NAMED_ENUM mapping instead of as varchar. A bulk update skips entity
    // callbacks, so updatedAt is set here (TenantEntityListener otherwise
    // would). flushAutomatically writes the caller's pending changes first -
    // an invoice marked PAID, a dunning schedule - so they are not left behind.

    @Modifying(flushAutomatically = true)
    @Query("""
            update SubscriptionEntity s
               set s.status = :next, s.updatedAt = :now
             where s.tenantId = :tenantId and s.id = :id and s.status = :expected
            """)
    int updateStatusIfStatus(@Param("tenantId") String tenantId, @Param("id") UUID id,
                             @Param("expected") SubscriptionStatus expected, @Param("next") SubscriptionStatus next,
                             @Param("now") Instant now);

    @Modifying(flushAutomatically = true)
    @Query("""
            update SubscriptionEntity s
               set s.status = :canceled, s.canceledAt = :canceledAt, s.updatedAt = :canceledAt
             where s.tenantId = :tenantId and s.id = :id and s.status = :expected
            """)
    int cancelIfStatus(@Param("tenantId") String tenantId, @Param("id") UUID id,
                       @Param("expected") SubscriptionStatus expected, @Param("canceledAt") Instant canceledAt,
                       @Param("canceled") SubscriptionStatus canceled);

    default int cancelIfStatus(String tenantId, UUID id, SubscriptionStatus expected, Instant canceledAt) {
        return cancelIfStatus(tenantId, id, expected, canceledAt, SubscriptionStatus.CANCELED);
    }

    @Modifying(flushAutomatically = true)
    @Query("""
            update SubscriptionEntity s
               set s.status = :active,
                   s.currentPeriodStart = :newPeriodStart,
                   s.currentPeriodEnd = :newPeriodEnd,
                   s.nextRenewal = :newPeriodEnd,
                   s.updatedAt = :now
             where s.tenantId = :tenantId and s.id = :id
               and s.status = :expectedStatus and s.currentPeriodEnd = :expectedPeriodEnd
            """)
    int renewIfCurrent(@Param("tenantId") String tenantId, @Param("id") UUID id,
                       @Param("expectedStatus") SubscriptionStatus expectedStatus,
                       @Param("expectedPeriodEnd") Instant expectedPeriodEnd,
                       @Param("newPeriodStart") Instant newPeriodStart, @Param("newPeriodEnd") Instant newPeriodEnd,
                       @Param("now") Instant now, @Param("active") SubscriptionStatus active);

    default int renewIfCurrent(String tenantId, UUID id, SubscriptionStatus expectedStatus, Instant expectedPeriodEnd,
                               Instant newPeriodStart, Instant newPeriodEnd, Instant now) {
        return renewIfCurrent(tenantId, id, expectedStatus, expectedPeriodEnd, newPeriodStart, newPeriodEnd, now,
                SubscriptionStatus.ACTIVE);
    }
}
