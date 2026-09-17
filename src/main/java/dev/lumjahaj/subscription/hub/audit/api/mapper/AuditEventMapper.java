package dev.lumjahaj.subscription.hub.audit.api.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.lumjahaj.subscription.hub.audit.api.dto.AuditEventResponse;
import dev.lumjahaj.subscription.hub.audit.infra.jpa.AuditEventEntity;
import org.springframework.stereotype.Component;

/**
 * A bean rather than a static utility for the same reason as
 * PlanEntitlementMapper: the jsonb column is stored as a String and returned
 * as a JSON object, which needs ObjectMapper.
 */
@Component
public class AuditEventMapper {

    private final ObjectMapper objectMapper;

    public AuditEventMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AuditEventResponse toResponse(AuditEventEntity entity) {
        return new AuditEventResponse(
                entity.getId(),
                entity.getType(),
                entity.getEntityType(),
                entity.getEntityId(),
                entity.getActorType(),
                entity.getActorId(),
                readData(entity.getData()),
                entity.getRequestId(),
                entity.getCreatedAt()
        );
    }

    private JsonNode readData(String data) {
        if (data == null) {
            return null;
        }
        try {
            return objectMapper.readTree(data);
        } catch (JsonProcessingException e) {
            // AuditService wrote it with writeValueAsString, so this means
            // the stored row is corrupt, not a normal runtime condition.
            throw new IllegalStateException("Stored audit event data is not valid JSON", e);
        }
    }
}
