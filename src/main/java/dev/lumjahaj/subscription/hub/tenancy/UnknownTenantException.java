package dev.lumjahaj.subscription.hub.tenancy;

public class UnknownTenantException extends RuntimeException {
    public UnknownTenantException(String tenantId) {
        super("Unknown or inactive tenant: " + tenantId);
    }
}
