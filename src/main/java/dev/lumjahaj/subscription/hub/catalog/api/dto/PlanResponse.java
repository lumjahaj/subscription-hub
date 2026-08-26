package dev.lumjahaj.subscription.hub.catalog.api.dto;

import java.time.Instant;
import java.util.UUID;

public record PlanResponse(
        UUID id,
        String productCode,
        String code,
        String name,
        String interval,
        long amountCents,
        String currency,
        int trialDays,
        Instant createdAt,
        Instant updatedAt
) {
}
