package dev.lumjahaj.subscription.hub.catalog.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * value is a genuine JSON value (object, number, string, boolean —
 * whatever the caller needs), not a JSON-encoded string. This matches
 * PlanEntitlementEntity.valueJson being a jsonb column: the mapper
 * flattens it to a String only at the point of persisting.
 * <p>
 * planId/planCode intentionally NOT here — the parent plan comes from
 * the URL path (/api/plans/{code}/entitlements), same as productCode
 * was left out of PlanCreateRequest's parent reference.
 */
public record PlanEntitlementCreateRequest(

        @NotBlank(message = "key is required")
        @Size(max = 64, message = "key must be at most 64 characters")
        String key,

        @NotNull(message = "value is required")
        JsonNode value

) {
}
