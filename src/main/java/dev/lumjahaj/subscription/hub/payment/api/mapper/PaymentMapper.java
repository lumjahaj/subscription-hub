package dev.lumjahaj.subscription.hub.payment.api.mapper;

import dev.lumjahaj.subscription.hub.payment.api.dto.PaymentResponse;
import dev.lumjahaj.subscription.hub.payment.infra.jpa.PaymentEntity;

// No toEntity(): a payment's amount, currency, status and provider are all
// decided by PaymentService, not copied from the request.
public final class PaymentMapper {

    private PaymentMapper() {
    }

    public static PaymentResponse toResponse(PaymentEntity entity) {
        return new PaymentResponse(
                entity.getId(),
                entity.getInvoice().getId(),
                entity.getStatus(),
                entity.getAmountCents(),
                entity.getCurrency(),
                entity.getProvider(),
                entity.getPaymentMethod(),
                entity.getProviderReference(),
                entity.getFailureCode(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
