package dev.lumjahaj.subscription.hub.auth.app;

import dev.lumjahaj.subscription.hub.common.api.BusinessRuleViolationException;
import org.springframework.http.HttpStatus;

/**
 * Raised for every login failure - unknown tenant, unknown email,
 * disabled account, wrong password - with one message and one code.
 *
 * Distinguishing them would let an unauthenticated caller enumerate which
 * tenants exist and which emails are registered in them, which is exactly
 * the reconnaissance a login endpoint should not provide. AuthService
 * also runs the password hash even when no user was found, so the
 * response time doesn't leak the same thing the message doesn't.
 */
public class InvalidCredentialsException extends BusinessRuleViolationException {

    public InvalidCredentialsException() {
        super("Invalid credentials", "INVALID_CREDENTIALS", HttpStatus.UNAUTHORIZED);
    }
}
