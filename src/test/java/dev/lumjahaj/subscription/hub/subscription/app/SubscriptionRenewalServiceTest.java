package dev.lumjahaj.subscription.hub.subscription.app;

import dev.lumjahaj.subscription.hub.catalog.domain.IntervalUnit;
import dev.lumjahaj.subscription.hub.catalog.infra.jpa.PlanEntity;
import dev.lumjahaj.subscription.hub.subscription.app.SubscriptionRenewalService.Renewal;
import dev.lumjahaj.subscription.hub.subscription.domain.SubscriptionStatus;
import dev.lumjahaj.subscription.hub.subscription.infra.jpa.SubscriptionEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionRenewalServiceTest {

    private static final Instant NOW = Instant.parse("2026-02-15T00:00:00Z");

    private static SubscriptionEntity subscription(SubscriptionStatus status, Instant periodEnd, Instant nextRenewal) {
        PlanEntity plan = new PlanEntity();
        plan.setIntervalUnit(IntervalUnit.MONTH);

        SubscriptionEntity entity = new SubscriptionEntity();
        entity.setPlan(plan);
        entity.setStatus(status);
        entity.setCurrentPeriodStart(periodEnd.minusSeconds(30L * 24 * 3600));
        entity.setCurrentPeriodEnd(periodEnd);
        entity.setNextRenewal(nextRenewal);
        return entity;
    }

    @Test
    void renewalFor_movesTrialingIntoAPaidPeriodWhenDue() {
        Instant trialEnd = Instant.parse("2026-02-14T00:00:00Z");
        SubscriptionEntity sub = subscription(SubscriptionStatus.TRIALING, trialEnd, trialEnd);

        // Becoming ACTIVE is part of every renewal; renewIfCurrent writes it.
        assertThat(SubscriptionRenewalService.renewalFor(sub, NOW))
                .contains(new Renewal(trialEnd, Instant.parse("2026-03-14T00:00:00Z")));
    }

    @Test
    void renewalFor_rollsPeriodForwardWhenActiveAndDue() {
        Instant oldEnd = Instant.parse("2026-02-14T00:00:00Z");
        SubscriptionEntity sub = subscription(SubscriptionStatus.ACTIVE, oldEnd, oldEnd);

        assertThat(SubscriptionRenewalService.renewalFor(sub, NOW))
                .contains(new Renewal(oldEnd, Instant.parse("2026-03-14T00:00:00Z")));
    }

    @Test
    void renewalFor_anchorsNewPeriodStartToOldPeriodEndNotNow() {
        // "now" is deliberately well past the due date, simulating job lag -
        // the new period must not drift to be anchored on "now".
        Instant oldEnd = Instant.parse("2026-02-01T00:00:00Z");
        SubscriptionEntity sub = subscription(SubscriptionStatus.ACTIVE, oldEnd, oldEnd);

        assertThat(SubscriptionRenewalService.renewalFor(sub, NOW))
                .contains(new Renewal(oldEnd, Instant.parse("2026-03-01T00:00:00Z")));
    }

    @Test
    void renewalFor_advancesExactlyOnePeriodEvenWhenSeverelyOverdue() {
        // Six months overdue - only one interval should be applied; the
        // result staying in the past is expected, it'll be picked up again
        // on the next run.
        Instant longOverdueEnd = Instant.parse("2025-08-15T00:00:00Z");
        SubscriptionEntity sub = subscription(SubscriptionStatus.ACTIVE, longOverdueEnd, longOverdueEnd);

        assertThat(SubscriptionRenewalService.renewalFor(sub, NOW))
                .map(Renewal::periodEnd)
                .contains(Instant.parse("2025-09-15T00:00:00Z"));
    }

    @Test
    void renewalFor_rollsByThePlansIntervalCountForQuarterlyBilling() {
        Instant oldEnd = Instant.parse("2026-02-14T00:00:00Z");
        SubscriptionEntity sub = subscription(SubscriptionStatus.ACTIVE, oldEnd, oldEnd);
        sub.getPlan().setIntervalCount(3);

        assertThat(SubscriptionRenewalService.renewalFor(sub, NOW))
                .map(Renewal::periodEnd)
                .contains(Instant.parse("2026-05-14T00:00:00Z"));
    }

    @Test
    void renewalFor_isEmptyWhenNotYetDue() {
        Instant futureEnd = Instant.parse("2026-03-01T00:00:00Z");
        SubscriptionEntity sub = subscription(SubscriptionStatus.ACTIVE, futureEnd, futureEnd);

        assertThat(SubscriptionRenewalService.renewalFor(sub, NOW)).isEmpty();
    }

    @Test
    void renewalFor_isEmptyWhenPaused() {
        Instant pastEnd = Instant.parse("2026-02-01T00:00:00Z");
        SubscriptionEntity sub = subscription(SubscriptionStatus.PAUSED, pastEnd, pastEnd);

        assertThat(SubscriptionRenewalService.renewalFor(sub, NOW)).isEmpty();
    }

    @Test
    void renewalFor_isEmptyWhenCanceled() {
        Instant pastEnd = Instant.parse("2026-02-01T00:00:00Z");
        SubscriptionEntity sub = subscription(SubscriptionStatus.CANCELED, pastEnd, pastEnd);

        assertThat(SubscriptionRenewalService.renewalFor(sub, NOW)).isEmpty();
    }

    /**
     * The new period's length comes from the plan being moved onto. Taking it
     * from the plan being left would put a yearly subscription in a one-month
     * period and bill it twelve times a year.
     */
    @Test
    void renewalFor_takesTheIntervalFromAScheduledPlanChange() {
        Instant oldEnd = Instant.parse("2026-02-14T00:00:00Z");
        SubscriptionEntity sub = subscription(SubscriptionStatus.ACTIVE, oldEnd, oldEnd);

        PlanEntity annual = new PlanEntity();
        annual.setIntervalUnit(IntervalUnit.YEAR);
        annual.setIntervalCount(1);
        sub.setPendingPlan(annual);

        assertThat(SubscriptionRenewalService.renewalFor(sub, NOW))
                .contains(new Renewal(oldEnd, Instant.parse("2027-02-14T00:00:00Z")));
    }

    @Test
    void renewalFor_withNoScheduledChange_staysOnTheCurrentPlansInterval() {
        Instant oldEnd = Instant.parse("2026-02-14T00:00:00Z");
        SubscriptionEntity sub = subscription(SubscriptionStatus.ACTIVE, oldEnd, oldEnd);

        assertThat(SubscriptionRenewalService.effectivePlanOf(sub)).isSameAs(sub.getPlan());
        assertThat(SubscriptionRenewalService.renewalFor(sub, NOW))
                .contains(new Renewal(oldEnd, Instant.parse("2026-03-14T00:00:00Z")));
    }

    @Test
    void renewalFor_neverModifiesTheSubscription() {
        // A modified managed entity is written at commit, unconditionally -
        // which is exactly how a renewal used to overwrite a concurrent cancel.
        Instant oldEnd = Instant.parse("2026-02-14T00:00:00Z");
        SubscriptionEntity sub = subscription(SubscriptionStatus.TRIALING, oldEnd, oldEnd);
        Instant oldStart = sub.getCurrentPeriodStart();

        SubscriptionRenewalService.renewalFor(sub, NOW);

        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.TRIALING);
        assertThat(sub.getCurrentPeriodStart()).isEqualTo(oldStart);
        assertThat(sub.getCurrentPeriodEnd()).isEqualTo(oldEnd);
        assertThat(sub.getNextRenewal()).isEqualTo(oldEnd);
    }
}
