package dev.lumjahaj.subscription.hub.catalog.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response payload representing a Product.
 * tenantId is intentionally NOT exposed here — the client already knows
 * its own tenant (it's the one sending X-Tenant-Id); echoing it back adds
 * no value and starts a habit of leaking internal fields through DTOs.
 */
public record ProductResponse(
        UUID id,
        String code,
        String name,
        String description,
        Instant createdAt,
        Instant updatedAt
) {
}