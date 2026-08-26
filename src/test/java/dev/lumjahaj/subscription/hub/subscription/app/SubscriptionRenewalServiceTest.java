package dev.lumjahaj.subscription.hub.subscription.app;

import dev.lumjahaj.subscription.hub.catalog.infra.jpa.PlanEntity;
import dev.lumjahaj.subscription.hub.subscription.domain.SubscriptionStatus;
import dev.lumjahaj.subscription.hub.subscription.infra.jpa.SubscriptionEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionRenewalServiceTest {

    private static final Instant NOW = Instant.parse("2026-02-15T00:00:00Z");

    private static SubscriptionEntity subscription(SubscriptionStatus status, Instant periodEnd, Instant nextRenewal) {
        PlanEntity plan = new PlanEntity();
        plan.setInterval("MONTH");

        SubscriptionEntity entity = new SubscriptionEntity();
        entity.setPlan(plan);
        entity.setStatus(status);
        entity.setCurrentPeriodStart(periodEnd.minusSeconds(30L * 24 * 3600));
        entity.setCurrentPeriodEnd(periodEnd);
        entity.setNextRenewal(nextRenewal);
        return entity;
    }

    @Test
    void applyRenewal_movesTrialingToActiveWhenDue() {
        SubscriptionEntity sub = subscription(
                SubscriptionStatus.TRIALING,
                Instant.parse("2026-02-14T00:00:00Z"),
                Instant.parse("2026-02-14T00:00:00Z"));

        boolean changed = SubscriptionRenewalService.applyRenewal(sub, NOW);

        assertThat(changed).isTrue();
        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
    }

    @Test
    void applyRenewal_rollsPeriodForwardWhenActiveAndDue() {
        Instant oldEnd = Instant.parse("2026-02-14T00:00:00Z");
        SubscriptionEntity sub = subscription(SubscriptionStatus.ACTIVE, oldEnd, oldEnd);

        boolean changed = SubscriptionRenewalService.applyRenewal(sub, NOW);

        assertThat(changed).isTrue();
        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(sub.getCurrentPeriodEnd()).isEqualTo(Instant.parse("2026-03-14T00:00:00Z"));
        assertThat(sub.getNextRenewal()).isEqualTo(sub.getCurrentPeriodEnd());
    }

    @Test
    void applyRenewal_anchorsNewPeriodStartToOldPeriodEndNotNow() {
        // "now" is deliberately well past the due date, simulating job lag -
        // the new period must not drift to be anchored on "now".
        Instant oldEnd = Instant.parse("2026-02-01T00:00:00Z");
        SubscriptionEntity sub = subscription(SubscriptionStatus.ACTIVE, oldEnd, oldEnd);

        SubscriptionRenewalService.applyRenewal(sub, NOW);

        assertThat(sub.getCurrentPeriodStart()).isEqualTo(oldEnd);
        assertThat(sub.getCurrentPeriodEnd()).isEqualTo(Instant.parse("2026-03-01T00:00:00Z"));
    }

    @Test
    void applyRenewal_advancesExactlyOnePeriodEvenWhenSeverelyOverdue() {
        // Six months overdue - only one interval should be applied; the
        // result staying in the past is expected, it'll be picked up again
        // on the next run.
        Instant longOverdueEnd = Instant.parse("2025-08-15T00:00:00Z");
        SubscriptionEntity sub = subscription(SubscriptionStatus.ACTIVE, longOverdueEnd, longOverdueEnd);

        SubscriptionRenewalService.applyRenewal(sub, NOW);

        assertThat(sub.getCurrentPeriodEnd()).isEqualTo(Instant.parse("2025-09-15T00:00:00Z"));
    }

    @Test
    void applyRenewal_doesNothingWhenNotYetDue() {
        Instant futureEnd = Instant.parse("2026-03-01T00:00:00Z");
        SubscriptionEntity sub = subscription(SubscriptionStatus.ACTIVE, futureEnd, futureEnd);

        boolean changed = SubscriptionRenewalService.applyRenewal(sub, NOW);

        assertThat(changed).isFalse();
        assertThat(sub.getCurrentPeriodEnd()).isEqualTo(futureEnd);
    }

    @Test
    void applyRenewal_doesNothingWhenPaused() {
        Instant pastEnd = Instant.parse("2026-02-01T00:00:00Z");
        SubscriptionEntity sub = subscription(SubscriptionStatus.PAUSED, pastEnd, pastEnd);

        boolean changed = SubscriptionRenewalService.applyRenewal(sub, NOW);

        assertThat(changed).isFalse();
        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.PAUSED);
    }

    @Test
    void applyRenewal_doesNothingWhenCanceled() {
        Instant pastEnd = Instant.parse("2026-02-01T00:00:00Z");
        SubscriptionEntity sub = subscription(SubscriptionStatus.CANCELED, pastEnd, pastEnd);

        boolean changed = SubscriptionRenewalService.applyRenewal(sub, NOW);

        assertThat(changed).isFalse();
        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.CANCELED);
    }
}
