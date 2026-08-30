package dev.lumjahaj.subscription.hub.common.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.lumjahaj.subscription.hub.common.logging.MdcKeys;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Renders Spring Security's own rejections as RFC 7807 problem+json.
 *
 * Without this the API has two error formats. Security rejects a request
 * inside the filter chain, before any controller is dispatched, so
 * ProblemDetailsAdvice never sees it — the defaults are an empty 401 body
 * with a WWW-Authenticate header, and an HTML 403 page. Every other error
 * this API produces carries `code` and `requestId`, and monitoring that
 * keys on those would go blind exactly when requests start being
 * rejected.
 *
 * One class implements both hooks so the two responses cannot drift apart.
 */
@Component
public class SecurityProblemHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public SecurityProblemHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** No credentials, or credentials that failed to validate. */
    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        // Deliberately does not echo authException.getMessage(): the reason
        // a token failed (expired, bad signature, wrong issuer) is useful
        // to an attacker probing for a forgery that sticks.
        write(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                "Authentication is required to access this resource.");
    }

    /** Authenticated, but lacking the role the endpoint requires. */
    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {
        write(response, HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                "You do not have permission to perform this action.");
    }

    private void write(HttpServletResponse response, HttpStatus status, String code, String detail)
            throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        // Present because RequestIdFilter runs at HIGHEST_PRECEDENCE,
        // outside the security chain — which is the whole reason it was
        // split out of TenantResolverFilter.
        problem.setProperty("requestId", MDC.get(MdcKeys.REQUEST_ID));

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
