package dev.lumjahaj.subscription.hub.catalog.api.dto;

import dev.lumjahaj.subscription.hub.catalog.domain.IntervalUnit;

import java.time.Instant;
import java.util.UUID;

public record PlanResponse(
        UUID id,
        String productCode,
        String code,
        String name,
        IntervalUnit intervalUnit,
        int intervalCount,
        long amountCents,
        String currency,
        int trialDays,
        Instant createdAt,
        Instant updatedAt
) {
}
