package dev.lumjahaj.subscription.hub.auth.domain;

import dev.lumjahaj.subscription.hub.auth.infra.jpa.AppUserEntity;

import java.util.Optional;

public interface AppUserRepository {

    /**
     * Tenant-scoped like every other lookup in the project: emails are
     * unique per tenant, not globally (uk_app_user_tenant_email), so the
     * same person can hold an account in two tenants and logging in has
     * to say which one.
     *
     * Note this runs before any tenant is in TenantContext — it is the
     * call that establishes which tenant the caller belongs to — so the
     * tenantId is passed explicitly rather than read from the context.
     */
    Optional<AppUserEntity> findByTenantIdAndEmail(String tenantId, String email);

    /**
     * Used only by tenant provisioning, to create a new tenant's first
     * administrator. The entity's tenantId must already be set: nothing
     * fills it in from TenantContext, which is empty on a platform request.
     */
    AppUserEntity save(AppUserEntity user);
}
