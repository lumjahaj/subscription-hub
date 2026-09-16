package dev.lumjahaj.subscription.hub.platform.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The one request body in the API that carries a tenant id, and correctly
 * so: here the tenant is the thing being created, not the caller's scope.
 */
public record TenantCreateRequest(

        // The slug ends up in every token's tenant_id claim, every log line's
        // MDC and every invoice PDF's object key, so it is held to a shape
        // that is safe in all three. Lowercase so "Acme" and "acme" can never
        // be two tenants. No underscores, which also makes the __no_tenant__
        // sentinel TenantIdentifierResolver uses impossible to provision. 63
        // characters, inside the column's 64.
        @NotBlank(message = "id is required")
        @Pattern(regexp = "^[a-z][a-z0-9-]{1,62}$",
                message = "id must be 2-63 characters of lowercase letters, digits and hyphens, starting with a letter")
        String id,

        @NotBlank(message = "name is required")
        @Size(max = 200, message = "name must be at most 200 characters")
        String name,

        @NotBlank(message = "adminEmail is required")
        @Email(message = "adminEmail must be a valid email address")
        String adminEmail

) {
}
