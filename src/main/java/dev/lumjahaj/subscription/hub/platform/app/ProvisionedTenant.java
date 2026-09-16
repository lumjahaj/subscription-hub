package dev.lumjahaj.subscription.hub.platform.app;

import dev.lumjahaj.subscription.hub.tenancy.domain.Tenant;

/**
 * The result of provisioning: the tenant plus the one moment its first
 * administrator's password exists in plaintext. Never stored, never logged.
 */
public record ProvisionedTenant(Tenant tenant, String adminEmail, String initialPassword) {
}
