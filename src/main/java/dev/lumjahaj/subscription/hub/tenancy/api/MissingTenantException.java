package dev.lumjahaj.subscription.hub.tenancy.api;

import dev.lumjahaj.subscription.hub.common.api.BusinessRuleViolationException;
import org.springframework.http.HttpStatus;

/**
 * The request authenticated but its token carries no usable tenant_id
 * claim.
 *
 * This used to mean "no X-Tenant-Id header", back when the tenant was
 * whatever the caller said it was. Since the tenant comes from a signed
 * claim, a missing one is no longer an ordinary client mistake — it means
 * a token was issued by something other than AuthService, so it is rare
 * and worth surfacing distinctly rather than folding into a generic 401.
 *
 * Extends BusinessRuleViolationException (rather than RuntimeException
 * directly) so ProblemDetailsAdvice maps it without common depending on
 * tenancy — see BusinessRuleViolationException's javadoc.
 */
public class MissingTenantException extends BusinessRuleViolationException {
    public MissingTenantException() {
        super("Authenticated token carries no tenant.", "TENANT_MISSING", HttpStatus.BAD_REQUEST);
    }
}
