package dev.lumjahaj.subscription.hub.subscription.app;

import dev.lumjahaj.subscription.hub.catalog.domain.IntervalUnit;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * Shared by subscription creation and renewal processing — both need to
 * advance a point in time by one plan billing period (unit x count).
 */
final class BillingPeriods {

    private BillingPeriods() {
    }

    /**
     * Instant has no notion of "a month" or "a year" (those are
     * calendar-based, not fixed-duration) — plus(1, ChronoUnit.MONTHS)
     * on an Instant throws UnsupportedTemporalTypeException. Converting
     * to ZonedDateTime (UTC) first gives access to plusMonths/plusYears,
     * then converting back to Instant for storage.
     *
     * The switch is exhaustive over IntervalUnit with no default branch —
     * unlike the old String-typed version, an invalid unit simply can't
     * be constructed, so there's nothing left to guard against here.
     */
    static Instant addInterval(Instant start, IntervalUnit unit, int count) {
        ZonedDateTime zdt = start.atZone(ZoneOffset.UTC);
        ZonedDateTime end = switch (unit) {
            case MONTH -> zdt.plusMonths(count);
            case YEAR -> zdt.plusYears(count);
        };
        return end.toInstant();
    }
}
