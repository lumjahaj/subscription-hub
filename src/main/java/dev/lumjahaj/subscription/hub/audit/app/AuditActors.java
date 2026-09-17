package dev.lumjahaj.subscription.hub.audit.app;

import dev.lumjahaj.subscription.hub.audit.domain.ActorType;
import dev.lumjahaj.subscription.hub.audit.domain.AuditActor;
import dev.lumjahaj.subscription.hub.auth.domain.PlatformRole;
import dev.lumjahaj.subscription.hub.tenancy.api.TenantResolverFilter;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Works out who is acting from the authentication, so no caller of
 * AuditService passes an actor in, and none can pass the wrong one.
 *
 * <p>The two token shapes are told apart the same way SecurityConfig tells
 * them apart: a tenant_id claim means a tenant user, and the PLATFORM_ADMIN
 * role means a platform administrator. No JWT at all means nobody
 * authenticated this work. That covers a scheduled job, or a provider
 * webhook on a permitAll path (which carries Spring's anonymous token, not a
 * JWT), and both are SYSTEM.
 *
 * <p>A JWT that is neither is refused rather than recorded as SYSTEM.
 * SecurityConfig should never let one reach a write. If one ever does, the
 * log must not mislabel a real principal as "the system".
 */
final class AuditActors {

    private static final String PLATFORM_ADMIN_AUTHORITY = "ROLE_" + PlatformRole.PLATFORM_ADMIN;

    private AuditActors() {
    }

    static AuditActor from(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwt)) {
            return AuditActor.SYSTEM;
        }
        if (jwt.getToken().hasClaim(TenantResolverFilter.TENANT_CLAIM)) {
            return new AuditActor(ActorType.USER, jwt.getName());
        }
        boolean platformAdmin = jwt.getAuthorities().stream()
                .anyMatch(authority -> PLATFORM_ADMIN_AUTHORITY.equals(authority.getAuthority()));
        if (platformAdmin) {
            return new AuditActor(ActorType.PLATFORM_ADMIN, jwt.getName());
        }
        throw new IllegalStateException("Cannot attribute an audit event to token subject " + jwt.getName());
    }

    /**
     * A tenant user can only ever produce events in its own tenant. Every
     * current caller takes the tenant from TenantContext, which was filled from
     * this same token, so this cannot fire today. It is here so that a future
     * caller passing a tenant from somewhere else fails loudly instead of
     * writing into another tenant's audit log.
     */
    static void requireActorMayRecordFor(Authentication authentication, String tenantId) {
        if (authentication instanceof JwtAuthenticationToken jwt
                && jwt.getToken().hasClaim(TenantResolverFilter.TENANT_CLAIM)) {
            String tokenTenant = jwt.getToken().getClaimAsString(TenantResolverFilter.TENANT_CLAIM);
            if (!tenantId.equals(tokenTenant)) {
                throw new IllegalStateException(
                        "A user of tenant " + tokenTenant + " cannot record audit events for " + tenantId);
            }
        }
    }
}
