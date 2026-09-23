package dev.lumjahaj.subscription.hub.subscription.infra.jpa;

import dev.lumjahaj.subscription.hub.catalog.infra.jpa.PlanEntity;
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

    // plan and pendingPlan are fetched on the finders behind API responses,
    // because SubscriptionMapper reads planCode and pendingPlanCode after the
    // transaction has ended (see PlanJpaRepository). customer is not: the
    // mapper reads only its id, which a Hibernate proxy answers without
    // loading anything. pendingPlan is nullable, so it fetches as a LEFT JOIN
    // and adds no rows.
    @EntityGraph(attributePaths = {"plan", "pendingPlan"})
    Page<SubscriptionEntity> findByTenantId(String tenantId, Pageable pageable);

    @EntityGraph(attributePaths = {"plan", "pendingPlan"})
    Page<SubscriptionEntity> findByTenantIdAndCustomerId(String tenantId, UUID customerId, Pageable pageable);

    @EntityGraph(attributePaths = {"plan", "pendingPlan"})
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

    /**
     * Sets or clears the pending plan, only if the row still holds the pending
     * plan the caller read. Scheduling a plan change is a state command like
     * the three above, not an edit of something read earlier, so it gets a
     * conditional update rather than @Version - a caller asking for a change
     * that is already scheduled should get the no-op, not a 409.
     *
     * The guard is what makes this compose with renewIfCurrent: whichever of
     * the two lands first, the other misses and re-reads rather than writing
     * over it.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update SubscriptionEntity s
               set s.pendingPlan = :newPendingPlan, s.updatedAt = :now
             where s.tenantId = :tenantId and s.id = :id and s.status <> :canceled
               and ((:expectedPendingPlan is null and s.pendingPlan is null)
                    or s.pendingPlan = :expectedPendingPlan)
            """)
    int setPendingPlanIfPending(@Param("tenantId") String tenantId, @Param("id") UUID id,
                                @Param("expectedPendingPlan") PlanEntity expectedPendingPlan,
                                @Param("newPendingPlan") PlanEntity newPendingPlan,
                                @Param("now") Instant now, @Param("canceled") SubscriptionStatus canceled);

    default int setPendingPlanIfPending(String tenantId, UUID id, PlanEntity expectedPendingPlan,
                                        PlanEntity newPendingPlan, Instant now) {
        return setPendingPlanIfPending(tenantId, id, expectedPendingPlan, newPendingPlan, now,
                SubscriptionStatus.CANCELED);
    }

    /**
     * Also applies a scheduled plan change, because a renewal is the moment one
     * takes effect and the two must be one statement. Splitting them would let
     * the renewal commit and the plan swap fail, leaving the row in a period
     * whose length came from a plan it is not on.
     *
     * expectedPendingPlan is in the WHERE for the same reason expectedPeriodEnd
     * is: newPeriodEnd was computed from the effective plan's interval, so a
     * plan change committing in between would move the row onto a plan whose
     * interval was never used - a monthly-to-yearly change landing here would
     * produce a one-month period on a yearly plan. On a miss the renewal is a
     * no-op and the next run decides again from the new state.
     *
     * The null branch has to be spelled out: pending_plan_id = NULL is never
     * true in SQL, so a plain equality would make every renewal of a
     * subscription with no pending change miss.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update SubscriptionEntity s
               set s.status = :active,
                   s.plan = :newPlan,
                   s.pendingPlan = null,
                   s.currentPeriodStart = :newPeriodStart,
                   s.currentPeriodEnd = :newPeriodEnd,
                   s.nextRenewal = :newPeriodEnd,
                   s.updatedAt = :now
             where s.tenantId = :tenantId and s.id = :id
               and s.status = :expectedStatus and s.currentPeriodEnd = :expectedPeriodEnd
               and ((:expectedPendingPlan is null and s.pendingPlan is null)
                    or s.pendingPlan = :expectedPendingPlan)
            """)
    int renewIfCurrent(@Param("tenantId") String tenantId, @Param("id") UUID id,
                       @Param("expectedStatus") SubscriptionStatus expectedStatus,
                       @Param("expectedPeriodEnd") Instant expectedPeriodEnd,
                       @Param("expectedPendingPlan") PlanEntity expectedPendingPlan,
                       @Param("newPlan") PlanEntity newPlan,
                       @Param("newPeriodStart") Instant newPeriodStart, @Param("newPeriodEnd") Instant newPeriodEnd,
                       @Param("now") Instant now, @Param("active") SubscriptionStatus active);

    default int renewIfCurrent(String tenantId, UUID id, SubscriptionStatus expectedStatus, Instant expectedPeriodEnd,
                               PlanEntity expectedPendingPlan, PlanEntity newPlan,
                               Instant newPeriodStart, Instant newPeriodEnd, Instant now) {
        return renewIfCurrent(tenantId, id, expectedStatus, expectedPeriodEnd, expectedPendingPlan, newPlan,
                newPeriodStart, newPeriodEnd, now, SubscriptionStatus.ACTIVE);
    }
}
