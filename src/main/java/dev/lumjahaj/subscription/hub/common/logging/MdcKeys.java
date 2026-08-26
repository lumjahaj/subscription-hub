package dev.lumjahaj.subscription.hub.common.logging;

/**
 * MDC key names shared across modules. Lives in common so anything —
 * ProblemDetailsAdvice included — can reference the request ID key
 * without depending on tenancy specifically. TenantResolverFilter is
 * still the thing that populates these values; this class only owns
 * the key names.
 */
public final class MdcKeys {

    public static final String REQUEST_ID = "requestId";
    public static final String TENANT_ID = "tenantId";

    private MdcKeys() {
    }
}