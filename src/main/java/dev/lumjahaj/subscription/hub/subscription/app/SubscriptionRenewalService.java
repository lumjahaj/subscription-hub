package dev.lumjahaj.subscription.hub.subscription.app;

import dev.lumjahaj.subscription.hub.catalog.infra.jpa.PlanEntity;
import dev.lumjahaj.subscription.hub.common.metrics.AfterCommit;
import dev.lumjahaj.subscription.hub.subscription.domain.SubscriptionRepository;
import dev.lumjahaj.subscription.hub.subscription.domain.SubscriptionStatus;
import dev.lumjahaj.subscription.hub.subscription.infra.jpa.SubscriptionEntity;
import dev.lumjahaj.subscription.hub.tenancy.domain.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class SubscriptionRenewalService {

    private final SubscriptionRepository subscriptions;
    private final Counter renewals;

    public SubscriptionRenewalService(SubscriptionRepository subscriptions, MeterRegistry registry) {
        this.subscriptions = subscriptions;
        // Renewals are deliberately not audited (high volume, derivable), so
        // they get their own counter rather than appearing in audit.events.
        this.renewals = Counter.builder("subscription.renewals")
                .description("Subscriptions rolled forward one billing period")
                .register(registry);
    }

    /**
     * Reloads the subscription inside this transaction rather than
     * accepting an already-loaded entity, so the lazy `plan` association
     * is still fetchable here — there's no open-session-in-view outside
     * a web request. Returns whether anything actually changed, so a
     * caller (or a test) can tell a no-op apart from a real transition.
     *
     * Callable directly with a single id: this is the seam a future
     * Stripe webhook handler calls into once it knows exactly which
     * subscription renewed, bypassing BillingCycleJob's "find due ones" scan
     * entirely.
     */
    @Transactional
    public boolean renewIfDue(UUID subscriptionId, Instant now) {
        String tenantId = TenantContext.getTenantId();
        SubscriptionEntity subscription = subscriptions.findByTenantIdAndId(tenantId, subscriptionId)
                .orElseThrow(() -> new IllegalStateException(
                        "Subscription " + subscriptionId + " not found for tenant " + tenantId + " during renewal"));

        boolean changed = applyRenewal(subscription, now);
        if (changed) {
            subscriptions.save(subscription);
            AfterCommit.run(renewals::increment);
        }
        return changed;
    }

    /**
     * Pure — no repository, no clock read, no Spring. This is the actual
     * business rule, kept separate from loading/saving.
     *
     * The new period is anchored to the *old* currentPeriodEnd, not to
     * `now`: if the job runs late, anchoring to `now` would push the
     * billing date later every cycle, compounding drift forever.
     * Anchoring to the old end means job lag never shifts the schedule.
     *
     * Advances exactly one period per call. A subscription that's months
     * overdue (e.g. the job was down) still only advances once here —
     * it stays due and gets picked up again next run. That makes this
     * self-healing and idempotent: a subscription that's already been
     * renewed no longer matches the "due" query, so calling this twice
     * on the same instant is harmless.
     */
    static boolean applyRenewal(SubscriptionEntity subscription, Instant now) {
        SubscriptionStatus status = subscription.getStatus();
        if (status != SubscriptionStatus.TRIALING && status != SubscriptionStatus.ACTIVE) {
            return false;
        }

        Instant nextRenewal = subscription.getNextRenewal();
        if (nextRenewal == null || nextRenewal.isAfter(now)) {
            return false;
        }

        Instant oldPeriodEnd = subscription.getCurrentPeriodEnd();
        PlanEntity plan = subscription.getPlan();
        Instant newPeriodEnd = BillingPeriods.addInterval(oldPeriodEnd, plan.getIntervalUnit(), plan.getIntervalCount());

        subscription.setStatus(SubscriptionStatus.ACTIVE);
        subscription.setCurrentPeriodStart(oldPeriodEnd);
        subscription.setCurrentPeriodEnd(newPeriodEnd);
        subscription.setNextRenewal(newPeriodEnd);
        return true;
    }
}
