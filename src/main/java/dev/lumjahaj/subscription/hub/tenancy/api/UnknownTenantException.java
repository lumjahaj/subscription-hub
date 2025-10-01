package dev.lumjahaj.subscription.hub.tenancy.api;

public class UnknownTenantException extends RuntimeException {
    public UnknownTenantException(String tenantId) {
        super("Unknown or inactive tenant: " + tenantId);
    }
}
