package dev.lumjahaj.subscription.hub.subscription.api.mapper;

import dev.lumjahaj.subscription.hub.subscription.api.dto.SubscriptionResponse;
import dev.lumjahaj.subscription.hub.subscription.infra.jpa.SubscriptionEntity;

/**
 * No toEntity() here, unlike Product/Plan/Customer — a Subscription isn't
 * built directly from a flat request; its fields (status, periods) are
 * computed by SubscriptionService based on business rules (trial vs
 * active), not copied 1:1 from the request. So this mapper only goes
 * one direction: entity -> response.
 */
public final class SubscriptionMapper {

    private SubscriptionMapper() {
    }

    public static SubscriptionResponse toResponse(SubscriptionEntity entity) {
        return new SubscriptionResponse(
                entity.getId(),
                entity.getCustomer().getId(),
                entity.getPlan().getCode(),
                entity.getPendingPlan() != null ? entity.getPendingPlan().getCode() : null,
                entity.getStatus(),
                entity.getStartAt(),
                entity.getCurrentPeriodStart(),
                entity.getCurrentPeriodEnd(),
                entity.getNextRenewal(),
                entity.getCancelAt(),
                entity.getCanceledAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
