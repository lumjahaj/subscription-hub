package dev.lumjahaj.subscription.hub.common.api;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the check-then-save race: two requests both pass the service's
 * findByTenantIdAndCode().ifPresent(throw) check, then both save() — the
 * second save fails on the DB unique constraint instead of the earlier
 * application-level check. Before this handler existed, that
 * DataIntegrityViolationException fell through to the generic 500 handler.
 */
class ProblemDetailsAdviceTest {

    private final ProblemDetailsAdvice advice = new ProblemDetailsAdvice();

    @Test
    void mapsKnownUniqueConstraintViolationTo409() {
        DataIntegrityViolationException ex = wrapping("uk_product_tenant_code");

        ProblemDetail problem = advice.handleDataIntegrityViolation(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getProperties()).containsEntry("code", "PRODUCT_ALREADY_EXISTS");
    }

    @Test
    void mapsEachRegisteredConstraintToItsOwnResourceType() {
        assertThat(codeFor("uk_customer_tenant_email")).isEqualTo("CUSTOMER_ALREADY_EXISTS");
        assertThat(codeFor("uk_plan_tenant_code")).isEqualTo("PLAN_ALREADY_EXISTS");
        assertThat(codeFor("uk_plan_ent_tenant_plan_key")).isEqualTo("PLANENTITLEMENT_ALREADY_EXISTS");
    }

    @Test
    void unrecognizedConstraintFallsBackTo500() {
        DataIntegrityViolationException ex = wrapping("some_other_constraint");

        ProblemDetail problem = advice.handleDataIntegrityViolation(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(problem.getProperties()).containsEntry("code", "INTERNAL_ERROR");
    }

    @Test
    void violationWithNoUnderlyingConstraintExceptionFallsBackTo500() {
        DataIntegrityViolationException ex = new DataIntegrityViolationException("no cause here");

        ProblemDetail problem = advice.handleDataIntegrityViolation(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
    }

    private String codeFor(String constraintName) {
        ProblemDetail problem = advice.handleDataIntegrityViolation(wrapping(constraintName));
        return (String) problem.getProperties().get("code");
    }

    private static DataIntegrityViolationException wrapping(String constraintName) {
        ConstraintViolationException cause = new ConstraintViolationException(
                "duplicate key value violates unique constraint",
                new SQLException("duplicate key"),
                constraintName);
        return new DataIntegrityViolationException("could not execute statement", cause);
    }
}
