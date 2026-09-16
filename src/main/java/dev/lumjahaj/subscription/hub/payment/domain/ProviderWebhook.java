package dev.lumjahaj.subscription.hub.payment.domain;

import java.util.Optional;

/**
 * Turns a raw provider webhook request into a {@link PaymentEvent}, after
 * proving the provider sent it.
 *
 * A port for the same reason {@link PaymentGateway} is one: the signature
 * scheme and the event format are the provider's, and only its adapter
 * should know them. Everything inward sees a PaymentEvent.
 */
public interface ProviderWebhook {

    /**
     * @param payload   the raw request body, exactly as received — any
     *                  re-serialization changes the bytes the signature
     *                  covers and verification fails
     * @param signature the provider's signature header
     * @return empty when the event is one we don't act on (an unrelated
     *         event type, or a payment not created by this application),
     *         which the caller answers 200 to rather than treating as an error
     * @throws WebhookVerificationException if the signature is missing,
     *         malformed, stale or wrong
     */
    Optional<PaymentEvent> parse(String payload, String signature);
}
