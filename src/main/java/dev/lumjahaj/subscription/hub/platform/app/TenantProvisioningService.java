package dev.lumjahaj.subscription.hub.platform.app;

import dev.lumjahaj.subscription.hub.auth.domain.AppUserRepository;
import dev.lumjahaj.subscription.hub.auth.domain.Role;
import dev.lumjahaj.subscription.hub.auth.infra.jpa.AppUserEntity;
import dev.lumjahaj.subscription.hub.common.api.ResourceAlreadyExistsException;
import dev.lumjahaj.subscription.hub.common.api.ResourceNotFoundException;
import dev.lumjahaj.subscription.hub.platform.api.dto.TenantCreateRequest;
import dev.lumjahaj.subscription.hub.tenancy.domain.Tenant;
import dev.lumjahaj.subscription.hub.tenancy.domain.TenantRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * Creates and manages tenants on behalf of a platform administrator.
 *
 * <p><b>Why a platform module rather than tenancy or auth.</b> Provisioning
 * writes both a tenant and an app_user. auth already depends on tenancy
 * (AuthService checks the tenant is active before login), so putting this in
 * tenancy would add tenancy → auth and close a cycle ArchUnit refuses. Putting
 * it in auth would make the login module the owner of tenant lifecycle. A
 * module above both is the only place it fits — the same forced shape as
 * dunning, which sits above billing and payment for the same reason.
 *
 * <p><b>No TenantContext here, and that is correct.</b> A platform request has
 * none, so the request's Hibernate session is pinned to the
 * {@code __no_tenant__} sentinel. Nothing below touches a TenantScoped entity
 * — tenant and app_user are the two tables that deliberately aren't — so the
 * sentinel never reaches a query. Reading tenant-owned data (a tenant's
 * invoices, say) from a platform endpoint would need the explicit session
 * handling PaymentWebhookService does, and would otherwise quietly return
 * nothing.
 */
@Service
public class TenantProvisioningService {

    private final TenantRepository tenants;
    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;

    public TenantProvisioningService(
            TenantRepository tenants,
            AppUserRepository users,
            PasswordEncoder passwordEncoder
    ) {
        this.tenants = tenants;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * The tenant and its first administrator in one transaction. A tenant
     * without an administrator could not be used — there is no
     * user-management API to add one later — so if the user insert fails,
     * the tenant must not survive it.
     */
    @Transactional
    public ProvisionedTenant provision(TenantCreateRequest request) {
        // Checked first for a clean 409 in the ordinary case; tenant_pkey
        // (mapped in ProblemDetailsAdvice) catches the concurrent one.
        tenants.findById(request.id()).ifPresent(existing -> {
            throw new ResourceAlreadyExistsException("Tenant", request.id());
        });

        Tenant tenant = tenants.create(new Tenant(request.id(), request.name(), true));

        String initialPassword = InitialPasswords.generate();
        AppUserEntity admin = new AppUserEntity();
        admin.setTenantId(tenant.id());
        admin.setEmail(request.adminEmail());
        admin.setPasswordHash(passwordEncoder.encode(initialPassword));
        admin.setRoles(Set.of(Role.ADMIN));
        users.save(admin);

        return new ProvisionedTenant(tenant, request.adminEmail(), initialPassword);
    }

    @Transactional(readOnly = true)
    public Page<Tenant> list(Pageable pageable) {
        return tenants.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public Tenant get(String id) {
        return tenants.findById(id).orElseThrow(() -> new ResourceNotFoundException("Tenant", id));
    }

    /**
     * Takes effect on the tenant's very next request, including with tokens
     * already issued: TenantResolverFilter re-checks active on every request
     * rather than trusting the token, and answers 401 TENANT_UNKNOWN. Nothing
     * is deleted — deactivation is reversible, and every tenant table
     * cascades from tenant, so a delete would destroy the tenant's invoices.
     *
     * Idempotent: deactivating an inactive tenant is a no-op, not a 409,
     * because the caller's intent ("this tenant must be off") is satisfied.
     */
    @Transactional
    public Tenant deactivate(String id) {
        return setActive(id, false);
    }

    @Transactional
    public Tenant activate(String id) {
        return setActive(id, true);
    }

    private Tenant setActive(String id, boolean active) {
        return tenants.setActive(id, active).orElseThrow(() -> new ResourceNotFoundException("Tenant", id));
    }
}
