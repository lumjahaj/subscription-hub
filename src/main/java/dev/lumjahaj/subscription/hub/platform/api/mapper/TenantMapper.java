package dev.lumjahaj.subscription.hub.platform.api.mapper;

import dev.lumjahaj.subscription.hub.platform.api.dto.TenantProvisionedResponse;
import dev.lumjahaj.subscription.hub.platform.api.dto.TenantResponse;
import dev.lumjahaj.subscription.hub.platform.app.ProvisionedTenant;
import dev.lumjahaj.subscription.hub.tenancy.domain.Tenant;

/**
 * Maps from the Tenant domain record rather than an entity, unlike the
 * catalog mappers: tenancy is the module with a real domain model
 * (CLAUDE.md §3), and the port already returns it.
 */
public final class TenantMapper {

    private TenantMapper() {
    }

    public static TenantResponse toResponse(Tenant tenant) {
        return new TenantResponse(tenant.id(), tenant.name(), tenant.active());
    }

    public static TenantProvisionedResponse toResponse(ProvisionedTenant provisioned) {
        return new TenantProvisionedResponse(
                toResponse(provisioned.tenant()),
                provisioned.adminEmail(),
                provisioned.initialPassword());
    }
}
