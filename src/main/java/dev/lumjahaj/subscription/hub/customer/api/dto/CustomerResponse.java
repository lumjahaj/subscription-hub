package dev.lumjahaj.subscription.hub.customer.api.dto;

import java.time.Instant;
import java.util.UUID;

public record CustomerResponse(
        UUID id,
        String externalId,
        String email,
        String name,

        // Whether automatic collection can charge this customer. A boolean
        // rather than the token itself: it is a provider credential of
        // sorts, and echoing it back would put it in logs and screenshots
        // for no benefit — the same reasoning as InvoiceResponse.pdfAvailable.
        boolean hasDefaultPaymentMethod,

        Instant createdAt,
        Instant updatedAt
) {
}
