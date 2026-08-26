package dev.lumjahaj.subscription.hub.customer.api.dto;

import java.time.Instant;
import java.util.UUID;

public record CustomerResponse(
        UUID id,
        String externalId,
        String email,
        String name,
        Instant createdAt,
        Instant updatedAt
) {
}
