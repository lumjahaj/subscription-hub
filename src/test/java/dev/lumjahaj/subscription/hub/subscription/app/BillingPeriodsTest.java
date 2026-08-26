package dev.lumjahaj.subscription.hub.subscription.app;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BillingPeriodsTest {

    @Test
    void addInterval_advancesOneMonthForMONTH() {
        Instant start = Instant.parse("2026-01-15T10:30:00Z");

        Instant end = BillingPeriods.addInterval(start, "MONTH");

        assertThat(end).isEqualTo(Instant.parse("2026-02-15T10:30:00Z"));
    }

    @Test
    void addInterval_advancesOneYearForYEAR() {
        Instant start = Instant.parse("2026-01-15T10:30:00Z");

        Instant end = BillingPeriods.addInterval(start, "YEAR");

        assertThat(end).isEqualTo(Instant.parse("2027-01-15T10:30:00Z"));
    }

    @Test
    void addInterval_clampsToShorterMonthOnMonthEnd() {
        Instant start = Instant.parse("2026-01-31T00:00:00Z");

        Instant end = BillingPeriods.addInterval(start, "MONTH");

        // ZonedDateTime.plusMonths clamps Jan 31 + 1 month to Feb 28
        // (2026 is not a leap year) rather than overflowing into March.
        assertThat(end).isEqualTo(Instant.parse("2026-02-28T00:00:00Z"));
    }

    @Test
    void addInterval_throwsOnUnknownInterval() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");

        assertThatThrownBy(() -> BillingPeriods.addInterval(start, "WEEK"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WEEK");
    }
}
