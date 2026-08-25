package dev.lumjahaj.subscription.hub.catalog.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record PlanEntitlementResponse(
        UUID id,
        String planCode,
        String key,
        JsonNode value,
        Instant createdAt,
        Instant updatedAt
) {
}
