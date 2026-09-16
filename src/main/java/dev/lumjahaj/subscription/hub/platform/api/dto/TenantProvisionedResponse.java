package dev.lumjahaj.subscription.hub.platform.api.dto;

/**
 * Returned by the create call only, and the only response in the API that
 * contains a password. It cannot be fetched again: GET returns
 * TenantResponse, and only the hash is stored. Losing it means an operator
 * resets it by hand today — there is no password reset yet.
 */
public record TenantProvisionedResponse(
        TenantResponse tenant,
        String adminEmail,
        String initialPassword
) {
}
