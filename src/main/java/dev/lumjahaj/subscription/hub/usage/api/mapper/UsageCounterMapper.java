package dev.lumjahaj.subscription.hub.usage.api.mapper;

import dev.lumjahaj.subscription.hub.usage.api.dto.UsageCounterResponse;
import dev.lumjahaj.subscription.hub.usage.infra.jpa.UsageCounterEntity;

// No toEntity() here, same reason as SubscriptionMapper - the entity is
// built by the upsert in UsageCounterRepositoryImpl, not copied 1:1 from
// a request.
public final class UsageCounterMapper {

    private UsageCounterMapper() {
    }

    public static UsageCounterResponse toResponse(UsageCounterEntity entity) {
        return new UsageCounterResponse(
                entity.getId(),
                entity.getSubscription().getId(),
                entity.getMeterKey(),
                entity.getPeriodStart(),
                entity.getPeriodEnd(),
                entity.getAmount(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
