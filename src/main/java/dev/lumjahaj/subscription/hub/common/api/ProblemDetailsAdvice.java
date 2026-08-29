package dev.lumjahaj.subscription.hub.common.api;

import dev.lumjahaj.subscription.hub.common.logging.MdcKeys;
import dev.lumjahaj.subscription.hub.tenancy.api.MissingTenantException;
import dev.lumjahaj.subscription.hub.tenancy.api.UnknownTenantException;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.Map;

@ControllerAdvice
public class ProblemDetailsAdvice {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsAdvice.class);

    // Maps a unique-constraint name to the resource type it protects, so a
    // race on check-then-save (two requests both pass the ifPresent check,
    // then both save) becomes the same 409 the check-then-save path itself
    // returns, instead of an unmapped DataIntegrityViolationException
    // falling through to a 500. Constraint names match the
    // @UniqueConstraint(name = ...) on each entity — see
    // V4__rename_unique_constraints_to_match_entities.sql, which renamed
    // Postgres's auto-generated names to these so the two stay in sync.
    private static final Map<String, String> UNIQUE_CONSTRAINT_RESOURCE_TYPES = Map.of(
            "uk_product_tenant_code", "Product",
            "uk_customer_tenant_email", "Customer",
            "uk_plan_tenant_code", "Plan",
            "uk_plan_ent_tenant_plan_key", "PlanEntitlement",
            "uk_invoice_tenant_number", "Invoice",
            "uk_invoice_tenant_sub_period", "Invoice"
    );

    private ProblemDetail base(HttpStatus status, String code, String message) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, message);
        pd.setProperty("code", code);
        pd.setProperty("requestId", MDC.get(MdcKeys.REQUEST_ID));
        return pd;
    }

    @ExceptionHandler(MissingTenantException.class)
    ProblemDetail handleMissingTenant(MissingTenantException ex) {
        return base(HttpStatus.BAD_REQUEST, "TENANT_MISSING", ex.getMessage());
    }

    @ExceptionHandler(UnknownTenantException.class)
    ProblemDetail handleUnknownTenant(UnknownTenantException ex) {
        return base(HttpStatus.UNAUTHORIZED, "TENANT_UNKNOWN", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        var pd = base(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed");
        pd.setProperty("details", ex.getBindingResult().getAllErrors()
                .stream().map(e -> e.getDefaultMessage()).toList());
        return pd;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        String resourceType = constraintViolationResourceType(ex);
        if (resourceType != null) {
            return base(HttpStatus.CONFLICT, resourceType.toUpperCase() + "_ALREADY_EXISTS",
                    resourceType + " already exists");
        }
        // Not a unique-constraint conflict we recognize (e.g. a FK or
        // not-null violation) — treat like any other unexpected failure.
        log.error("Unhandled data integrity violation", ex);
        return base(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected error");
    }

    private String constraintViolationResourceType(DataIntegrityViolationException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof ConstraintViolationException cve) {
            return UNIQUE_CONSTRAINT_RESOURCE_TYPES.get(cve.getConstraintName());
        }
        return null;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        return base(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected error");
    }

    @ExceptionHandler(ResourceAlreadyExistsException.class)
    ProblemDetail handleAlreadyExists(ResourceAlreadyExistsException ex) {
        return base(HttpStatus.CONFLICT, ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ProblemDetail handleNotFound(ResourceNotFoundException ex) {
        return base(HttpStatus.NOT_FOUND, ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(BusinessRuleViolationException.class)
    ProblemDetail handleBusinessRuleViolation(BusinessRuleViolationException ex) {
        return base(ex.getStatus(), ex.getCode(), ex.getMessage());
    }
}
