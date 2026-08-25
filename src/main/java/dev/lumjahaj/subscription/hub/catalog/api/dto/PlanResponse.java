package dev.lumjahaj.subscription.hub.catalog.api.dto;

import java.time.Instant;
import java.util.UUID;

public record PlanResponse(
        UUID id,
        String code,
        String name,
        Instant createdAt,
        Instant updatedAt
) {
}
