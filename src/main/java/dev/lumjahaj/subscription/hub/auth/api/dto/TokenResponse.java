package dev.lumjahaj.subscription.hub.auth.api.dto;

import java.time.Instant;
import java.util.Set;

/**
 * tokenType is spelled out rather than assumed: the value belongs in an
 * "Authorization: Bearer <token>" header, and saying so is cheaper than
 * making every client author guess.
 *
 * roles and expiresAt are echoed for the client's convenience only. They
 * are already inside the signed token, and nothing server-side ever
 * trusts what a client sends back - these are for rendering a UI, not for
 * authorization.
 */
public record TokenResponse(
        String token,
        String tokenType,
        Instant expiresAt,
        String tenantId,
        Set<String> roles
) {
}
