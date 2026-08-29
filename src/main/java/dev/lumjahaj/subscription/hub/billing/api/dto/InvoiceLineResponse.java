package dev.lumjahaj.subscription.hub.billing.api.dto;

import dev.lumjahaj.subscription.hub.billing.domain.InvoiceLineKind;

import java.math.BigDecimal;
import java.util.UUID;

// No createdAt/updatedAt: a line has no independent lifecycle, its
// timestamps are always its invoice's.
public record InvoiceLineResponse(
        UUID id,
        InvoiceLineKind kind,
        String description,
        BigDecimal quantity,
        long unitAmountCents,
        long amountCents
) {
}
