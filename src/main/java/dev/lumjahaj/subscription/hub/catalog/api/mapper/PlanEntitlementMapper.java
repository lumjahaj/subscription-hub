package dev.lumjahaj.subscription.hub.catalog.api.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.lumjahaj.subscription.hub.catalog.api.dto.PlanEntitlementCreateRequest;
import dev.lumjahaj.subscription.hub.catalog.api.dto.PlanEntitlementResponse;
import dev.lumjahaj.subscription.hub.catalog.infra.jpa.PlanEntitlementEntity;
import org.springframework.stereotype.Component;

/**
 * NOT static like ProductMapper/PlanMapper — this one needs ObjectMapper
 * to convert between the request/response JsonNode and the entity's
 * String valueJson column, so it's a real dependency to inject rather
 * than a stateless utility method.
 */
@Component
public class PlanEntitlementMapper {

    private final ObjectMapper objectMapper;

    public PlanEntitlementMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Caller (app service) is responsible for setting tenantId and plan
     * before persisting — neither comes from the request body.
     */
    public PlanEntitlementEntity toEntity(PlanEntitlementCreateRequest request) {
        PlanEntitlementEntity entity = new PlanEntitlementEntity();
        entity.setKey(request.key());
        try {
            entity.setValueJson(objectMapper.writeValueAsString(request.value()));
        } catch (JsonProcessingException e) {
            // request.value() is already a parsed JsonNode from Jackson's own
            // deserialization, so re-serializing it should not realistically
            // fail — wrapping rather than declaring a checked exception here
            // keeps the mapper's method signature clean.
            throw new IllegalStateException("Failed to serialize entitlement value", e);
        }
        return entity;
    }

    public PlanEntitlementResponse toResponse(PlanEntitlementEntity entity) {
        JsonNode value;
        try {
            value = objectMapper.readTree(entity.getValueJson());
        } catch (JsonProcessingException e) {
            // Data already persisted as valid JSON (we wrote it via
            // writeValueAsString above) — a failure here means the DB
            // content is corrupt, not a normal runtime condition.
            throw new IllegalStateException("Stored entitlement value is not valid JSON", e);
        }
        return new PlanEntitlementResponse(
                entity.getId(),
                entity.getPlan().getCode(),
                entity.getKey(),
                value,
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
