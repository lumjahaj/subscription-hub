package dev.lumjahaj.subscription.hub.common.api;

import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import dev.lumjahaj.subscription.hub.common.logging.MdcKeys;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

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

    // A required query parameter that is absent. No endpoint declares one
    // today, so this is latent: without it, the first one to do so would
    // report a caller's omission as a 500.
    @ExceptionHandler(MissingServletRequestParameterException.class)
    ProblemDetail handleMissingParameter(MissingServletRequestParameterException ex) {
        var pd = base(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed");
        pd.setProperty("details", List.of(ex.getParameterName() + " parameter is required"));
        return pd;
    }

    // A body Jackson cannot turn into the request DTO: not JSON at all, no
    // body where one is required, or a field of the wrong type
    // ("amountCents": "ten"). A distinct code from VALIDATION_ERROR, because
    // validation never ran - there was no object to validate.
    //
    // Jackson's own message names the target class (PlanCreateRequest) and
    // quotes the rejected value, so it is replaced: the field's path is
    // enough to fix the request, for the same reason handleTypeMismatch
    // does not echo input.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail handleUnreadableBody(HttpMessageNotReadableException ex) {
        var pd = base(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST_BODY", "Request body could not be read");
        pd.setProperty("details", List.of(describeUnreadable(ex)));
        return pd;
    }

    private static String describeUnreadable(HttpMessageNotReadableException ex) {
        if (ex.getCause() instanceof MismatchedInputException mismatch && !mismatch.getPath().isEmpty()) {
            String field = mismatch.getPath().stream()
                    .map(ref -> ref.getFieldName() != null ? ref.getFieldName() : "[" + ref.getIndex() + "]")
                    .collect(Collectors.joining("."));
            return field + " has an invalid value";
        }
        return "request body is missing or is not valid JSON";
    }

    // The path exists but not for this method (DELETE /api/products). The
    // exception carries the Allow header, which RFC 9110 requires on a 405,
    // so its headers are passed through rather than dropped.
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ProblemDetail> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .headers(ex.getHeaders())
                .body(base(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                        "This endpoint does not support the request method"));
    }

    // A body in a format no endpoint reads (text/plain, form data). The
    // exception's headers carry Accept, listing what would have worked -
    // which is more than application/json (application/*+json, and YAML via
    // the Jackson YAML converter on the classpath), so the message says JSON
    // in general rather than naming one media type the header contradicts.
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ProblemDetail> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .headers(ex.getHeaders())
                .body(base(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                        "Request body must be JSON; the Accept header lists the supported types"));
    }

    // No handler for the URL at all. Only an authenticated caller gets this
    // far - Security answers 401 first - so it does not let an anonymous one
    // map which endpoints exist. Distinct from the resource-level
    // <TYPE>_NOT_FOUND codes: those mean "no such record", this means "no
    // such endpoint", and a client should react differently to each.
    //
    // The detail does not repeat the path, but the response still contains
    // it: Spring sets ProblemDetail's "instance" to the request path on every
    // problem response. That is the caller's own URL inside a JSON string,
    // not reflected input in a markup context, so it is left alone.
    @ExceptionHandler(NoResourceFoundException.class)
    ProblemDetail handleNoEndpoint(NoResourceFoundException ex) {
        return base(HttpStatus.NOT_FOUND, "ENDPOINT_NOT_FOUND", "No endpoint exists at this path");
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

    // A @Version check failed at flush: another write committed between this
    // request's read and its write. Not logged as an error - it is the
    // mechanism working, and the caller only has to re-read and retry.
    // A stale If-Match caught before writing is the 412 instead; this is the
    // narrower race after that check passed.
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail handleOptimisticLockingFailure(OptimisticLockingFailureException ex) {
        return base(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                "The resource was modified by another request; read it again and retry");
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
