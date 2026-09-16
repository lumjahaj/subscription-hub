package dev.lumjahaj.subscription.hub.auth.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** No tenantId: a platform account belongs to no tenant, so there is no directory to choose. */
public record PlatformTokenRequest(

        @NotBlank(message = "email is required")
        @Email(message = "email must be a valid email address")
        String email,

        @NotBlank(message = "password is required")
        String password

) {
}
