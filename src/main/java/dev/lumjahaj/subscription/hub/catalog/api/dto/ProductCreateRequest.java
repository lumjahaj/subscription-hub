package dev.lumjahaj.subscription.hub.catalog.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload for creating a Product.
 * tenantId is intentionally NOT here — it comes from the X-Tenant-Id
 * header / tenant context, never from the request body.
 */
public record ProductCreateRequest(

        @NotBlank(message = "code is required")
        @Size(max = 64, message = "code must be at most 64 characters")
        String code,

        @NotBlank(message = "name is required")
        @Size(max = 255, message = "name must be at most 255 characters")
        String name,

        @Size(max = 1000, message = "description must be at most 1000 characters")
        String description

) {
}
