package dev.lumjahaj.subscription.hub.usage.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record UsageCounterResponse(
        UUID id,
        UUID subscriptionId,
        String meterKey,
        Instant periodStart,
        Instant periodEnd,
        BigDecimal amount,
        Instant createdAt,
        Instant updatedAt
) {
}
