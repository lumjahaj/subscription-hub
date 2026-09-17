package dev.lumjahaj.subscription.hub.dunning.app;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * When to try again, and when to stop trying.
 *
 * Pure: no repository, no clock, no Spring beyond reading its own
 * configuration — the same split SubscriptionRenewalService.renewalFor
 * and InvoiceCalculator use, so the policy can be tested as a table of
 * inputs and outputs rather than through a job and a database.
 *
 * The delays lengthen (a card declined twice in a day is unlikely to work
 * on the third try, while the same card next week might), and the list's
 * length plus one is the number of attempts: three delays means an initial
 * attempt and three retries.
 */
@Component
public class DunningSchedule {

    private final List<Duration> retryDelays;
    private final int maxAttempts;

    public DunningSchedule(
            @Value("${dunning.retry-delays}") List<Duration> retryDelays,
            @Value("${dunning.max-attempts}") int maxAttempts
    ) {
        if (retryDelays.isEmpty()) {
            throw new IllegalStateException("dunning.retry-delays must not be empty");
        }
        if (maxAttempts < 1) {
            throw new IllegalStateException("dunning.max-attempts must be at least 1");
        }
        this.retryDelays = List.copyOf(retryDelays);
        this.maxAttempts = maxAttempts;
    }

    /**
     * @param attemptCount attempts already started, so 1 immediately after
     *                     the first attempt
     * @return when the next attempt becomes due. The last configured delay
     *         repeats if there are more attempts than delays, so the two
     *         settings can be tuned independently without a gap.
     */
    public Instant nextAttemptAt(int attemptCount, Instant now) {
        int index = Math.min(Math.max(attemptCount - 1, 0), retryDelays.size() - 1);
        return now.plus(retryDelays.get(index));
    }

    /** True once the configured number of attempts has been started. */
    public boolean isExhausted(int attemptCount) {
        return attemptCount >= maxAttempts;
    }

    public int maxAttempts() {
        return maxAttempts;
    }
}
