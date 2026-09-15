package dev.lumjahaj.subscription.hub.tenancy.infra.jpa;

import dev.lumjahaj.subscription.hub.tenancy.domain.TenantContext;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.stereotype.Component;

/**
 * Wires Hibernate's row-level (@TenantId) multi-tenancy to the same
 * TenantContext the rest of the app already reads. Spring Boot does NOT
 * auto-detect this bean — it has to be registered explicitly as the
 * hibernate.tenant_identifier_resolver property, done in JpaConfig via a
 * HibernatePropertiesCustomizer.
 *
 * This makes the tenant_id predicate part of every query Hibernate builds
 * for a @TenantId-annotated entity, so a repository method that forgets to
 * scope by tenant still can't return another tenant's rows. It's an added
 * safety net, not a replacement for the explicit findByTenantId... methods,
 * which stay as the primary, intention-revealing contract.
 *
 * Hibernate requires a non-null tenant identifier to open a Session at
 * all — including internally, when Spring Data JPA probes for named
 * queries while building repository proxies at startup, before any
 * request (and therefore any TenantContext) exists. NO_TENANT is a
 * sentinel that satisfies that requirement without being a real tenant id:
 * no row's tenant_id column can ever equal it, so any query that somehow
 * runs with it (outside a request/job that sets TenantContext) returns
 * nothing rather than leaking data — fail closed, not fail open.
 */
@Component
public class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<String> {

    private static final String NO_TENANT = "__no_tenant__";

    @Override
    public String resolveCurrentTenantIdentifier() {
        String tenantId = TenantContext.getTenantId();
        return tenantId != null ? tenantId : NO_TENANT;
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return true;
    }
}