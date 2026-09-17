package dev.lumjahaj.subscription.hub.common.api;

import dev.lumjahaj.subscription.hub.common.logging.MdcKeys;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
            "uk_invoice_tenant_sub_period", "Invoice",
            // The one primary key here: a tenant's id is its natural key
            // (an assigned slug), so the PK is the uniqueness rule. Postgres's
            // default name, left as-is — V4's renames exist because entities
            // declared names the database didn't have, and TenantEntity
            // declares none.
            "tenant_pkey", "Tenant"
    );

    private ProblemDetail base(HttpStatus status, String code, String message) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, message);
        pd.setProperty("code", code);
        pd.setProperty("requestId", MDC.get(MdcKeys.REQUEST_ID));
        return pd;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        var pd = base(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed");
        pd.setProperty("details", ex.getBindingResult().getAllErrors()
                .stream().map(e -> e.getDefaultMessage()).toList());
        return pd;
    }

    // Constraints on a @RequestHeader/@PathVariable parameter (rather than a
    // @RequestBody) are enforced by Spring's built-in method validation,
    // which throws this instead of MethodArgumentNotValidException.
    @ExceptionHandler(HandlerMethodValidationException.class)
    ProblemDetail handleMethodValidation(HandlerMethodValidationException ex) {
        var pd = base(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed");
        pd.setProperty("details", ex.getAllErrors()
                .stream().map(e -> e.getDefaultMessage()).toList());
        return pd;
    }

    // A required header that is absent never reaches method validation.
    // Without this it fell through to the catch-all as a 500.
    @ExceptionHandler(MissingRequestHeaderException.class)
    ProblemDetail handleMissingHeader(MissingRequestHeaderException ex) {
        var pd = base(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed");
        pd.setProperty("details", List.of(ex.getHeaderName() + " header is required"));
        return pd;
    }

    // A query parameter or path variable that cannot be converted to its
    // declared type (?customerId=not-a-uuid, ?entityType=NOPE,
    // /api/subscriptions/123). Spring throws this before the controller runs,
    // and without a handler the caller's typo was reported as our 500.
    //
    // The rejected value is deliberately not echoed back: reflecting raw input
    // into a response is how an error body becomes an injection vector, and
    // the parameter name plus the expected shape is enough to fix the request.
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        var pd = base(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed");
        pd.setProperty("details", List.of(describeExpected(ex.getName(), ex.getRequiredType())));
        return pd;
    }

    // An unknown ?sort= property. Spring Data resolves the Pageable's sort
    // against the entity only when the query runs, so this surfaces from the
    // repository call rather than from argument binding. Its own message names
    // the entity class (ProductEntity), which is internal, so it is replaced.
    @ExceptionHandler(PropertyReferenceException.class)
    ProblemDetail handleUnknownSortProperty(PropertyReferenceException ex) {
        var pd = base(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed");
        pd.setProperty("details", List.of("sort refers to a property that does not exist"));
        return pd;
    }

    private static String describeExpected(String parameter, Class<?> requiredType) {
        if (requiredType != null && requiredType.isEnum()) {
            return parameter + " must be one of " + Arrays.toString(requiredType.getEnumConstants());
        }
        if (requiredType == UUID.class) {
            return parameter + " must be a UUID";
        }
        return parameter + " has an invalid value";
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

    /**
     * @PreAuthorize denials, which arrive here rather than at
     * SecurityProblemHandler.
     *
     * Method security runs as an interceptor around the controller, so its
     * AccessDeniedException is thrown during dispatch — inside
     * DispatcherServlet, where @ControllerAdvice gets first refusal. Without
     * this handler the catch-all below would swallow it and report a
     * denied request as 500 INTERNAL_ERROR: the endpoint would look broken
     * rather than forbidden, and the 403 would never reach the
     * ExceptionTranslationFilter that normally renders it.
     *
     * Same status and code SecurityProblemHandler produces for a
     * URL-level denial, so a client cannot tell which mechanism refused it.
     */
    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        return base(HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                "You do not have permission to perform this action.");
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
