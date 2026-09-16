package dev.lumjahaj.subscription.hub.platform.api.dto;

public record TenantResponse(
        String id,
        String name,
        boolean active
) {
}
