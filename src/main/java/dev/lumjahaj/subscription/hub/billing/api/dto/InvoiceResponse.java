package dev.lumjahaj.subscription.hub.billing.api.dto;

import dev.lumjahaj.subscription.hub.billing.domain.InvoiceStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record InvoiceResponse(
        UUID id,
        UUID subscriptionId,
        UUID customerId,
        String number,
        InvoiceStatus status,
        String currency,
        long totalCents,
        Instant periodStart,
        Instant periodEnd,
        Instant issuedAt,
        Instant dueAt,
        List<InvoiceLineResponse> lines,
        Instant createdAt,
        Instant updatedAt
) {
}
