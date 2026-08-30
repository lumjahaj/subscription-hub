package dev.lumjahaj.subscription.hub.auth.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * tenantId lives in the body here, and only here. Everywhere else the
 * tenant comes from the verified JWT claim - a client-supplied tenant is
 * exactly the hole authentication closes. On this one endpoint there is
 * no token yet, so the tenant is just selecting which tenant's user
 * directory to check, and the password still has to match.
 */
public record TokenRequest(

        @NotBlank(message = "tenantId is required")
        String tenantId,

        @NotBlank(message = "email is required")
        @Email(message = "email must be a valid email address")
        String email,

        @NotBlank(message = "password is required")
        String password

) {
}
