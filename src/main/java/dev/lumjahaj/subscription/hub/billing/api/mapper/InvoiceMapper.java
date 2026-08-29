package dev.lumjahaj.subscription.hub.billing.api.mapper;

import dev.lumjahaj.subscription.hub.billing.api.dto.InvoiceLineResponse;
import dev.lumjahaj.subscription.hub.billing.api.dto.InvoiceResponse;
import dev.lumjahaj.subscription.hub.billing.infra.jpa.InvoiceEntity;
import dev.lumjahaj.subscription.hub.billing.infra.jpa.InvoiceLineEntity;

// No toEntity(): an invoice is computed by InvoiceService, not copied 1:1
// from a request - same reason SubscriptionMapper and UsageCounterMapper
// have none. Static, not @Component: unlike PlanEntitlementMapper, no
// dependency is needed here - the entitlement jsonb is parsed in the
// service, not at this response boundary.
public final class InvoiceMapper {

    private InvoiceMapper() {
    }

    public static InvoiceResponse toResponse(InvoiceEntity entity) {
        return new InvoiceResponse(
                entity.getId(),
                entity.getSubscription().getId(),
                entity.getCustomer().getId(),
                entity.getNumber(),
                entity.getStatus(),
                entity.getCurrency(),
                entity.getTotalCents(),
                entity.getPeriodStart(),
                entity.getPeriodEnd(),
                entity.getIssuedAt(),
                entity.getDueAt(),
                entity.getLines().stream().map(InvoiceMapper::toLineResponse).toList(),
                entity.getPdfObjectKey() != null,
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private static InvoiceLineResponse toLineResponse(InvoiceLineEntity line) {
        return new InvoiceLineResponse(
                line.getId(),
                line.getKind(),
                line.getDescription(),
                line.getQuantity(),
                line.getUnitAmountCents(),
                line.getAmountCents()
        );
    }
}
