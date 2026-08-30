package dev.lumjahaj.subscription.hub.tenancy.api;

/**
 * The request authenticated but its token carries no usable tenant_id
 * claim.
 *
 * This used to mean "no X-Tenant-Id header", back when the tenant was
 * whatever the caller said it was. Since the tenant comes from a signed
 * claim, a missing one is no longer an ordinary client mistake — it means
 * a token was issued by something other than AuthService, so it is rare
 * and worth surfacing distinctly rather than folding into a generic 401.
 */
public class MissingTenantException extends RuntimeException {
    public MissingTenantException() {
        super("Authenticated token carries no tenant.");
    }
}
