package dev.lumjahaj.subscription.hub.tenancy.domain;

import java.util.function.Supplier;

public final class TenantContext {
    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

    /**
     * Stands in for "no tenant" wherever a tenant identifier is structurally
     * required but none is in context — Hibernate refuses to open a Session
     * without one, and the database connection is bound to one on every
     * borrow. No row's tenant_id can ever equal it: the provisioning slug rule
     * (^[a-z][a-z0-9-]{1,62}$) forbids underscores, and TenantResolverFilter
     * answers 401 for a tenant with no row. So anything that runs with it
     * reads nothing rather than leaking — fail closed, not fail open.
     *
     * Public because two adapters need exactly the same value and must not
     * drift: TenantIdentifierResolver (the @TenantId predicate) and
     * TenantAwareDataSource (the app.tenant_id setting the RLS policies read).
     */
    public static final String NO_TENANT = "__no_tenant__";

    private TenantContext() {}

    public static void setTenantId(String tenantId) { CURRENT_TENANT.set(tenantId); }
    public static String getTenantId() { return CURRENT_TENANT.get(); }
    public static void clear() { CURRENT_TENANT.remove(); }

    /**
     * Runs an action scoped to a tenant, guaranteeing cleanup afterwards.
     *
     * Request-scoped code gets its tenant from TenantResolverFilter, which
     * clears the ThreadLocal in a finally block. Background jobs have no
     * request, so they have to do the same thing by hand — this exists so
     * every future job gets that finally-clear for free instead of
     * reimplementing (and eventually forgetting) it.
     *
     * Leaving a tenant set on a pooled thread would silently scope the next
     * unrelated piece of work to the wrong tenant, so the cleanup is the
     * whole point.
     */
    public static void runAs(String tenantId, Runnable action) {
        try {
            setTenantId(tenantId);
            action.run();
        } finally {
            clear();
        }
    }

    /**
     * runAs for an action that returns something.
     *
     * Exists because of where the tenant now has to be set. Hibernate resolves
     * @TenantId when a session opens, and the database connection is bound to
     * app.tenant_id when it is borrowed — both of which happen when a
     * transaction begins. So setting the tenant *inside* a @Transactional
     * method is too late to affect either: the work is already scoped to
     * whatever was in context when the method was entered.
     *
     * Where a @Transactional service method is itself the entry point — login,
     * and the platform endpoints that act on a named tenant — the wrapping
     * therefore has to happen at the caller, and those callers return a value.
     */
    public static <T> T callAs(String tenantId, Supplier<T> action) {
        try {
            setTenantId(tenantId);
            return action.get();
        } finally {
            clear();
        }
    }
}
