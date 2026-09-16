package dev.lumjahaj.subscription.hub.payment.domain;

import java.util.UUID;

/**
 * A provider's report of a payment's outcome, translated out of the
 * provider's own format by its adapter. Settlement only ever sees this
 * type, so a new provider means a new translation, not new billing logic.
 *
 * @param eventId           the provider's event id, for logging and tracing
 * @param provider          must match the payment's provider, see PaymentGateway#provider
 * @param providerReference the provider's id for the payment
 * @param failureCode       set only when {@code outcome} is FAILED
 */
public record PaymentEvent(
        String eventId,
        String provider,
        String tenantId,
        UUID paymentId,
        String providerReference,
        Outcome outcome,
        String failureCode
) {

    public enum Outcome {
        SUCCEEDED,
        FAILED
    }
}
