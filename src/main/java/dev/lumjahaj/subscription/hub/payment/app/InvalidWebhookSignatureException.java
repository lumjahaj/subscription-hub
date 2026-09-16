package dev.lumjahaj.subscription.hub.payment.app;

import dev.lumjahaj.subscription.hub.common.api.BusinessRuleViolationException;
import org.springframework.http.HttpStatus;

/**
 * 400, deliberately, not 401/403: the provider's own documentation says an
 * endpoint that can't verify a signature should answer 400, and a 4xx tells
 * it to stop retrying a request that will never verify. The message stays
 * vague — anything more would help someone probing the endpoint.
 */
public class InvalidWebhookSignatureException extends BusinessRuleViolationException {

    public InvalidWebhookSignatureException() {
        super("Webhook signature could not be verified", "WEBHOOK_SIGNATURE_INVALID", HttpStatus.BAD_REQUEST);
    }
}
