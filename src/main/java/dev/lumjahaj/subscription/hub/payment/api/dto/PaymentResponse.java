package dev.lumjahaj.subscription.hub.payment.api.dto;

import dev.lumjahaj.subscription.hub.payment.domain.PaymentStatus;

import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        UUID invoiceId,
        PaymentStatus status,
        long amountCents,
        String currency,
        String provider,
        String paymentMethod,
        // The provider's own id, exposed so support can find the payment in
        // the provider's dashboard. Null until the provider acknowledges it.
        String providerReference,
        String failureCode,
        Instant createdAt,
        Instant updatedAt
) {
}
