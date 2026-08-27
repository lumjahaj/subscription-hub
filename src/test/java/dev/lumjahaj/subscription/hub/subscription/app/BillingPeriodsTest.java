package dev.lumjahaj.subscription.hub.subscription.app;

import dev.lumjahaj.subscription.hub.catalog.domain.IntervalUnit;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class BillingPeriodsTest {

    @Test
    void addInterval_advancesOneMonthForMONTH() {
        Instant start = Instant.parse("2026-01-15T10:30:00Z");

        Instant end = BillingPeriods.addInterval(start, IntervalUnit.MONTH, 1);

        assertThat(end).isEqualTo(Instant.parse("2026-02-15T10:30:00Z"));
    }

    @Test
    void addInterval_advancesOneYearForYEAR() {
        Instant start = Instant.parse("2026-01-15T10:30:00Z");

        Instant end = BillingPeriods.addInterval(start, IntervalUnit.YEAR, 1);

        assertThat(end).isEqualTo(Instant.parse("2027-01-15T10:30:00Z"));
    }

    @Test
    void addInterval_advancesByCountForQuarterlyBilling() {
        Instant start = Instant.parse("2026-01-15T00:00:00Z");

        Instant end = BillingPeriods.addInterval(start, IntervalUnit.MONTH, 3);

        assertThat(end).isEqualTo(Instant.parse("2026-04-15T00:00:00Z"));
    }

    @Test
    void addInterval_advancesByCountForBiennialBilling() {
        Instant start = Instant.parse("2026-01-15T00:00:00Z");

        Instant end = BillingPeriods.addInterval(start, IntervalUnit.YEAR, 2);

        assertThat(end).isEqualTo(Instant.parse("2028-01-15T00:00:00Z"));
    }

    @Test
    void addInterval_clampsToShorterMonthOnMonthEnd() {
        Instant start = Instant.parse("2026-01-31T00:00:00Z");

        Instant end = BillingPeriods.addInterval(start, IntervalUnit.MONTH, 1);

        // ZonedDateTime.plusMonths clamps Jan 31 + 1 month to Feb 28
        // (2026 is not a leap year) rather than overflowing into March.
        assertThat(end).isEqualTo(Instant.parse("2026-02-28T00:00:00Z"));
    }
}
