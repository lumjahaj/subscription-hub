package dev.lumjahaj.subscription.hub.common.api;

import dev.lumjahaj.subscription.hub.common.logging.MdcKeys;
import dev.lumjahaj.subscription.hub.tenancy.api.MissingTenantException;
import dev.lumjahaj.subscription.hub.tenancy.api.UnknownTenantException;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice
public class ProblemDetailsAdvice {

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

    @ExceptionHandler(Exception.class)
    ProblemDetail handleGeneric(Exception ex) {
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
}
