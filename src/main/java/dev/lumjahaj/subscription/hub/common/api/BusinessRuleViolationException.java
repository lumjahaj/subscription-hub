package dev.lumjahaj.subscription.hub.common.api;

import org.springframework.http.HttpStatus;

/**
 * Base for feature-module business-rule violations (invalid state
 * transitions, etc.) — the "difference carries real information" kind of
 * exception, as opposed to the generic ResourceNotFoundException /
 * ResourceAlreadyExistsException shapes.
 *
 * Feature modules extend this rather than ProblemDetailsAdvice importing
 * their exception types directly, so common stays dependency-free of every
 * feature module while still funneling every error through one advice.
 */
public abstract class BusinessRuleViolationException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    protected BusinessRuleViolationException(String message, String code, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
