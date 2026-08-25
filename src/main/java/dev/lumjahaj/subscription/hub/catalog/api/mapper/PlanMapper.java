package dev.lumjahaj.subscription.hub.catalog.api.mapper;

import dev.lumjahaj.subscription.hub.catalog.api.dto.PlanCreateRequest;
import dev.lumjahaj.subscription.hub.catalog.api.dto.PlanResponse;
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
        return entity;
    }

    public static PlanResponse toResponse(PlanEntity entity) {
        return new PlanResponse(
                entity.getId(),
                entity.getCode(),
                entity.getName(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
