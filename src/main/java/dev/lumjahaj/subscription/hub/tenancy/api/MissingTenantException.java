package dev.lumjahaj.subscription.hub.tenancy.api;

public class MissingTenantException extends RuntimeException {
    public MissingTenantException() {
        super("Missing X-Tenant-Id header.");
    }
}
