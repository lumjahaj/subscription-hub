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

        // Whether GET /api/invoices/{id}/pdf will succeed. Deliberately a
        // boolean rather than the stored object key: the key is internal
        // storage layout and is tenant-prefixed, so exposing it would leak
        // both where the bytes live and the tenant id that request bodies
        // and responses never carry.
        boolean pdfAvailable,

        Instant createdAt,
        Instant updatedAt
) {
}
