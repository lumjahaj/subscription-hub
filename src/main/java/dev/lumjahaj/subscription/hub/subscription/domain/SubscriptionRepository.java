package dev.lumjahaj.subscription.hub.subscription.domain;

import dev.lumjahaj.subscription.hub.subscription.infra.jpa.SubscriptionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Subscriptions are created with save, and after that change only through the
 * compare-and-set methods below - never by mutating a loaded entity and saving
 * it. Every change to a subscription is a state command (cancel, pause, resume,
 * past due, recover, renew), and a read-then-write command loses to a
 * concurrent one: a renewal that read ACTIVE would write ACTIVE back over a
 * cancellation committed in between.
 *
 * Each method is one conditional UPDATE that applies only if the row is still
 * in the state the caller observed, and returns whether it did. It is the rule
 * from CLAUDE.md §5 - conditional updates for state commands, @Version for edits
 * from an earlier read - applied to the entity with the most concurrent writers.
 */
public interface SubscriptionRepository {
    SubscriptionEntity save(SubscriptionEntity subscription);
    Page<SubscriptionEntity> findByTenantId(String tenantId, Pageable pageable);
    Page<SubscriptionEntity> findByTenantIdAndCustomerId(String tenantId, UUID customerId, Pageable pageable);
    Optional<SubscriptionEntity> findByTenantIdAndId(String tenantId, UUID id);
    List<SubscriptionEntity> findByTenantIdAndStatusInAndNextRenewalLessThanEqual(
            String tenantId, Collection<SubscriptionStatus> statuses, Instant cutoff);

    /** Sets the status to next only if it is still expected. Not for CANCELED: see cancelIfStatus. */
    boolean updateStatusIfStatus(String tenantId, UUID id, SubscriptionStatus expected, SubscriptionStatus next);

    /** Cancels, recording canceledAt, only if the status is still expected. */
    boolean cancelIfStatus(String tenantId, UUID id, SubscriptionStatus expected, Instant canceledAt);

    /**
     * Rolls the subscription into its next period, and makes it ACTIVE, only if
     * it is still in the status and the period the renewal was computed from. A
     * cancellation, a pause or an earlier renewal in between makes this a no-op
     * instead of something it silently overwrites.
     */
    boolean renewIfCurrent(String tenantId, UUID id, SubscriptionStatus expectedStatus, Instant expectedPeriodEnd,
                           Instant newPeriodStart, Instant newPeriodEnd);

    /**
     * The subscription as the database has it now. A plain find returns the
     * instance already in the persistence context, which is stale after a
     * conditional update fails because someone else changed the row.
     */
    Optional<SubscriptionEntity> findCurrentByTenantIdAndId(String tenantId, UUID id);
}
