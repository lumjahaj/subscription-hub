package dev.lumjahaj.subscription.hub.auth.api.dto;

import java.time.Instant;
import java.util.Set;

/**
 * TokenResponse without tenantId, rather than TokenResponse with a null
 * one: a "tenantId": null field in a login response reads like a bug in
 * the tenant login, where it would be one.
 */
public record PlatformTokenResponse(
        String token,
        String tokenType,
        Instant expiresAt,
        Set<String> roles
) {
}
