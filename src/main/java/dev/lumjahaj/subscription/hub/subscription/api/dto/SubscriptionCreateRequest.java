package dev.lumjahaj.subscription.hub.subscription.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * customerId is a UUID (Customer has no human-readable code, unlike
 * Product/Plan) — the client gets this back from POST /api/customers.
 * planCode is a code, consistent with how Plan is addressed elsewhere.
 */
public record SubscriptionCreateRequest(

        @NotNull(message = "customerId is required")
        UUID customerId,

        @NotBlank(message = "planCode is required")
        String planCode

) {
}
