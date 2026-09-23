package dev.lumjahaj.subscription.hub.subscription.infra.jpa;

import dev.lumjahaj.subscription.hub.catalog.infra.jpa.PlanEntity;
import dev.lumjahaj.subscription.hub.subscription.domain.SubscriptionRepository;
import dev.lumjahaj.subscription.hub.subscription.domain.SubscriptionStatus;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class SubscriptionRepositoryImpl implements SubscriptionRepository {

    private final SubscriptionJpaRepository jpaRepository;
    private final EntityManager entityManager;

    public SubscriptionRepositoryImpl(SubscriptionJpaRepository jpaRepository, EntityManager entityManager) {
        this.jpaRepository = jpaRepository;
        this.entityManager = entityManager;
    }

    @Override
    public SubscriptionEntity save(SubscriptionEntity subscription) {
        return jpaRepository.save(subscription);
    }

    @Override
    public Page<SubscriptionEntity> findByTenantId(String tenantId, Pageable pageable) {
        return jpaRepository.findByTenantId(tenantId, pageable);
    }

    @Override
    public Page<SubscriptionEntity> findByTenantIdAndCustomerId(String tenantId, UUID customerId, Pageable pageable) {
        return jpaRepository.findByTenantIdAndCustomerId(tenantId, customerId, pageable);
    }

    @Override
    public Optional<SubscriptionEntity> findByTenantIdAndId(String tenantId, UUID id) {
        return jpaRepository.findByTenantIdAndId(tenantId, id);
    }

    @Override
    public List<SubscriptionEntity> findByTenantIdAndStatusInAndNextRenewalLessThanEqual(
            String tenantId, Collection<SubscriptionStatus> statuses, Instant cutoff) {
        return jpaRepository.findByTenantIdAndStatusInAndNextRenewalLessThanEqual(tenantId, statuses, cutoff);
    }

    @Override
    public boolean updateStatusIfStatus(String tenantId, UUID id, SubscriptionStatus expected, SubscriptionStatus next) {
        if (next == SubscriptionStatus.CANCELED) {
            throw new IllegalArgumentException("Use cancelIfStatus, which also records canceledAt");
        }
        return refreshedIf(id, jpaRepository.updateStatusIfStatus(tenantId, id, expected, next, Instant.now()) == 1);
    }

    @Override
    public boolean cancelIfStatus(String tenantId, UUID id, SubscriptionStatus expected, Instant canceledAt) {
        return refreshedIf(id, jpaRepository.cancelIfStatus(tenantId, id, expected, canceledAt) == 1);
    }

    @Override
    public boolean setPendingPlanIfPending(String tenantId, UUID id, PlanEntity expectedPendingPlan,
                                           PlanEntity newPendingPlan) {
        return refreshedIf(id, jpaRepository.setPendingPlanIfPending(
                tenantId, id, expectedPendingPlan, newPendingPlan, Instant.now()) == 1);
    }

    @Override
    public boolean renewIfCurrent(String tenantId, UUID id, SubscriptionStatus expectedStatus, Instant expectedPeriodEnd,
                                  PlanEntity expectedPendingPlan, PlanEntity newPlan,
                                  Instant newPeriodStart, Instant newPeriodEnd) {
        return refreshedIf(id, jpaRepository.renewIfCurrent(
                tenantId, id, expectedStatus, expectedPeriodEnd, expectedPendingPlan, newPlan,
                newPeriodStart, newPeriodEnd, Instant.now()) == 1);
    }

    /**
     * Refreshes first, then queries - not the other way round, which is how
     * this was written until pendingPlan existed.
     *
     * findByTenantIdAndId join-fetches plan and pendingPlan. A query never
     * overwrites an entity the persistence context already holds, so if the
     * row's pending_plan_id changed since this transaction read it, Hibernate
     * assembles a row whose fetched association disagrees with the managed
     * instance and throws EntityFilterException ("is filtered for
     * association") rather than returning anything. Every caller here is on
     * the retry path after a conditional update missed, which means another
     * writer just changed the row - exactly when that mismatch is likely.
     *
     * Refreshing the managed instance first re-reads the row by id, with no
     * fetch graph, so the query that follows finds the context already
     * agreeing with the database. A plain scalar change (a status) never had
     * this problem, which is why the old order held until a nullable
     * association was added.
     */
    @Override
    public Optional<SubscriptionEntity> findCurrentByTenantIdAndId(String tenantId, UUID id) {
        refreshIfLoaded(id);
        return jpaRepository.findByTenantIdAndId(tenantId, id);
    }

    /**
     * A bulk UPDATE goes straight to the database and bypasses the persistence
     * context, so a subscription the caller has already loaded still holds the
     * old values. It is refreshed here after a change, so the caller's instance -
     * and the response mapped from it - shows what was written.
     *
     * Deliberately not @Modifying(clearAutomatically = true), as
     * TenantJpaRepository uses: these updates also run inside dunning's
     * settlement transaction, where clearing the whole persistence context would
     * detach the invoice and payment that transaction is still working with.
     */
    private boolean refreshedIf(UUID id, boolean changed) {
        if (changed) {
            refreshIfLoaded(id);
        }
        return changed;
    }

    /**
     * Re-reads the row into whatever instance the persistence context holds.
     * find() by id uses no fetch graph, so it cannot hit the association
     * mismatch described on findCurrentByTenantIdAndId.
     */
    private void refreshIfLoaded(UUID id) {
        SubscriptionEntity loaded = entityManager.find(SubscriptionEntity.class, id);
        if (loaded != null) {
            entityManager.refresh(loaded);
        }
    }
}
