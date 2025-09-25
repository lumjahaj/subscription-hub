package dev.lumjahaj.subscription.hub.tenancy;

public class MissingTenantException extends RuntimeException {
    public MissingTenantException() {
        super("Missing X-Tenant-Id header.");
    }
}
