package dev.lumjahaj.subscription.hub.subscription.app;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * Shared by subscription creation and renewal processing — both need to
 * advance a point in time by one plan interval.
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
     */
    static Instant addInterval(Instant start, String interval) {
        ZonedDateTime zdt = start.atZone(ZoneOffset.UTC);
        ZonedDateTime end = switch (interval) {
            case "MONTH" -> zdt.plusMonths(1);
            case "YEAR" -> zdt.plusYears(1);
            default -> throw new IllegalStateException("Unknown plan interval: " + interval);
        };
        return end.toInstant();
    }
}
