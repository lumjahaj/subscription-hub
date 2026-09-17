package dev.lumjahaj.subscription.hub.audit.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import dev.lumjahaj.subscription.hub.audit.domain.ActorType;
import dev.lumjahaj.subscription.hub.audit.domain.AuditEntityType;
import dev.lumjahaj.subscription.hub.audit.domain.AuditEventType;

import java.time.Instant;
import java.util.UUID;

public record AuditEventResponse(
        UUID id,
        AuditEventType type,
        AuditEntityType entityType,
        String entityId,
        ActorType actorType,
        String actorId,
        JsonNode data,
        String requestId,
        Instant createdAt
) {
}
