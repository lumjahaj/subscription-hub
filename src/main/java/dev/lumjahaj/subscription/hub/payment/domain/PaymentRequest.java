package dev.lumjahaj.subscription.hub.payment.domain;

import java.util.UUID;

/**
 * What an adapter sends to the provider. tenantId and paymentId travel as
 * provider metadata and come back on every event, which is how an event is
 * matched to its payment — by our id, not the provider's reference, so an
 * event that arrives before the reference has been stored still settles.
 */
public record PaymentRequest(
        String tenantId,
        UUID paymentId,
        long amountCents,
        String currency,
        String paymentMethod
) {
}
