package dev.lumjahaj.subscription.hub.catalog.api.mapper;

import dev.lumjahaj.subscription.hub.catalog.api.dto.PlanCreateRequest;
import dev.lumjahaj.subscription.hub.catalog.api.dto.PlanResponse;
import dev.lumjahaj.subscription.hub.catalog.domain.IntervalUnit;
import dev.lumjahaj.subscription.hub.catalog.infra.jpa.PlanEntity;

public final class PlanMapper {

    private PlanMapper() {
    }

    /**
     * Caller is responsible for setting tenantId (from the tenant context)
     * before persisting.
     */
    public static PlanEntity toEntity(PlanCreateRequest request) {
        PlanEntity entity = new PlanEntity();
        entity.setCode(request.code());
        entity.setName(request.name());
        entity.setIntervalUnit(IntervalUnit.valueOf(request.intervalUnit()));
        if (request.intervalCount() != null) {
            entity.setIntervalCount(request.intervalCount());
        }
        entity.setAmountCents(request.amountCents());
        if (request.currency() != null) {
            entity.setCurrency(request.currency());
        }
        if (request.trialDays() != null) {
            entity.setTrialDays(request.trialDays());
        }
        return entity;
    }
    public static PlanResponse toResponse(PlanEntity entity) {
        return new PlanResponse(
                entity.getId(),
                entity.getProduct().getCode(),
                entity.getCode(),
                entity.getName(),
                entity.getIntervalUnit(),
                entity.getIntervalCount(),
                entity.getAmountCents(),
                entity.getCurrency(),
                entity.getTrialDays(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
