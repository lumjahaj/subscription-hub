package dev.lumjahaj.subscription.hub.subscription.api.dto;

import dev.lumjahaj.subscription.hub.subscription.domain.SubscriptionStatus;

import java.time.Instant;
import java.util.UUID;

public record SubscriptionResponse(
        UUID id,
        UUID customerId,
        String planCode,
        // The plan this subscription moves onto at its next renewal, or null.
        // Nothing about the current period changes until then.
        String pendingPlanCode,
        SubscriptionStatus status,
        Instant startAt,
        Instant currentPeriodStart,
        Instant currentPeriodEnd,
        Instant nextRenewal,
        Instant cancelAt,
        Instant canceledAt,
        Instant createdAt,
        Instant updatedAt
) {
}
