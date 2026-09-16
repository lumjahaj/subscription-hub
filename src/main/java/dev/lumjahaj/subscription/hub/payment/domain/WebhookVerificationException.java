package dev.lumjahaj.subscription.hub.payment.domain;

/**
 * The request did not come from the provider — or at least can't be shown
 * to have. On a public, unauthenticated endpoint this is the only thing
 * standing in for authentication, so it is always fatal to the request.
 *
 * Plain runtime exception, not a BusinessRuleViolationException: the domain
 * has no business knowing HTTP status codes, so the app layer translates it.
 */
public class WebhookVerificationException extends RuntimeException {

    public WebhookVerificationException(String message) {
        super(message);
    }

    public WebhookVerificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
