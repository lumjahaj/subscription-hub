package dev.lumjahaj.subscription.hub.tenancy.api;

import dev.lumjahaj.subscription.hub.common.api.BusinessRuleViolationException;
import org.springframework.http.HttpStatus;

/**
 * Extends BusinessRuleViolationException (rather than RuntimeException
 * directly) so ProblemDetailsAdvice maps it without common depending on
 * tenancy — see BusinessRuleViolationException's javadoc.
 */
public class UnknownTenantException extends BusinessRuleViolationException {
    public UnknownTenantException(String tenantId) {
        super("Unknown or inactive tenant: " + tenantId, "TENANT_UNKNOWN", HttpStatus.UNAUTHORIZED);
    }
}
