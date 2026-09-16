package dev.lumjahaj.subscription.hub.dunning.app;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The retry policy on its own — no job, no database. This is the rule that
 * decides how long a customer's service survives a failing card, so it is
 * worth being able to read it as a table.
 */
class DunningScheduleTest {

    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");

    private final DunningSchedule schedule = new DunningSchedule(
            List.of(Duration.ofDays(1), Duration.ofDays(3), Duration.ofDays(5)), 4);

    @Test
    void nextAttempt_usesTheDelayForTheAttemptJustMade() {
        assertThat(schedule.nextAttemptAt(1, NOW)).isEqualTo(NOW.plus(Duration.ofDays(1)));
        assertThat(schedule.nextAttemptAt(2, NOW)).isEqualTo(NOW.plus(Duration.ofDays(3)));
        assertThat(schedule.nextAttemptAt(3, NOW)).isEqualTo(NOW.plus(Duration.ofDays(5)));
    }

    @Test
    void nextAttempt_beyondTheConfiguredDelays_repeatsTheLastOne() {
        // max-attempts and retry-delays are separate settings; if someone
        // raises one without the other, the schedule must still produce a
        // sane gap rather than an index out of bounds.
        assertThat(schedule.nextAttemptAt(4, NOW)).isEqualTo(NOW.plus(Duration.ofDays(5)));
        assertThat(schedule.nextAttemptAt(99, NOW)).isEqualTo(NOW.plus(Duration.ofDays(5)));
    }

    @Test
    void nextAttempt_beforeAnyAttempt_usesTheFirstDelay() {
        assertThat(schedule.nextAttemptAt(0, NOW)).isEqualTo(NOW.plus(Duration.ofDays(1)));
    }

    @Test
    void isExhausted_onlyOnceTheConfiguredAttemptsHaveAllBeenStarted() {
        assertThat(schedule.isExhausted(0)).isFalse();
        assertThat(schedule.isExhausted(3)).isFalse();
        assertThat(schedule.isExhausted(4)).isTrue();
        assertThat(schedule.isExhausted(5)).isTrue();
    }

    @Test
    void construction_rejectsAConfigurationThatCouldNeverRetry() {
        assertThatThrownBy(() -> new DunningSchedule(List.of(), 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("retry-delays");
        assertThatThrownBy(() -> new DunningSchedule(List.of(Duration.ofDays(1)), 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-attempts");
    }
}
