package dev.lumjahaj.subscription.hub.subscription.domain;

import dev.lumjahaj.subscription.hub.catalog.infra.jpa.PlanEntity;
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
     * Schedules the plan the subscription moves onto at its next renewal, or
     * clears it with a null newPendingPlan, only if the row still holds the
     * pending plan the caller read. Refused outright on a CANCELED
     * subscription, which will never renew.
     *
     * Nothing takes effect here: the plan is swapped by renewIfCurrent. That is
     * deliberate, because InvoiceCalculator reads the plan's price when the
     * invoice is generated and BillingCycleJob invoices before it renews, so
     * deferring the swap is what keeps the closed period billed at the price
     * the customer was actually on.
     */
    boolean setPendingPlanIfPending(String tenantId, UUID id, PlanEntity expectedPendingPlan,
                                    PlanEntity newPendingPlan);

    /**
     * Rolls the subscription into its next period, and makes it ACTIVE, only if
     * it is still in the status, the period and the pending plan the renewal was
     * computed from. A cancellation, a pause, an earlier renewal or a plan
     * change in between makes this a no-op instead of something it silently
     * overwrites.
     *
     * newPlan is the plan the subscription should be on afterwards - the pending
     * one if there is one, otherwise the current one - and any pending change is
     * cleared. It is applied here rather than in its own statement because the
     * new period's length was computed from that plan's interval, so the two
     * have to commit together or not at all.
     */
    boolean renewIfCurrent(String tenantId, UUID id, SubscriptionStatus expectedStatus, Instant expectedPeriodEnd,
                           PlanEntity expectedPendingPlan, PlanEntity newPlan,
                           Instant newPeriodStart, Instant newPeriodEnd);

    /**
     * The subscription as the database has it now. A plain find returns the
     * instance already in the persistence context, which is stale after a
     * conditional update fails because someone else changed the row.
     */
    Optional<SubscriptionEntity> findCurrentByTenantIdAndId(String tenantId, UUID id);
}
