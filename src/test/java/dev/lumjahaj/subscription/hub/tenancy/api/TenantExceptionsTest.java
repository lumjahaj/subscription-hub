package dev.lumjahaj.subscription.hub.tenancy.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Both exceptions extend BusinessRuleViolationException so
 * ProblemDetailsAdvice can map them without common depending on tenancy.
 * This covers the code/status pairing that mapping relies on — previously
 * unverified, since ProblemDetailsAdviceTest predates this refactor.
 */
class TenantExceptionsTest {

    @Test
    void missingTenantMapsToTenantMissing400() {
        MissingTenantException ex = new MissingTenantException();

        assertThat(ex.getCode()).isEqualTo("TENANT_MISSING");
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getMessage()).isEqualTo("Authenticated token carries no tenant.");
    }

    @Test
    void unknownTenantMapsToTenantUnknown401() {
        UnknownTenantException ex = new UnknownTenantException("ghost");

        assertThat(ex.getCode()).isEqualTo("TENANT_UNKNOWN");
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ex.getMessage()).isEqualTo("Unknown or inactive tenant: ghost");
    }
}
