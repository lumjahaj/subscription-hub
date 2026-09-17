package dev.lumjahaj.subscription.hub.audit.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.lumjahaj.subscription.hub.audit.domain.AuditActor;
import dev.lumjahaj.subscription.hub.audit.domain.AuditEntityType;
import dev.lumjahaj.subscription.hub.audit.domain.AuditEventRepository;
import dev.lumjahaj.subscription.hub.audit.domain.AuditEventType;
import dev.lumjahaj.subscription.hub.audit.infra.jpa.AuditEventEntity;
import dev.lumjahaj.subscription.hub.common.logging.MdcKeys;
import dev.lumjahaj.subscription.hub.tenancy.domain.TenantContext;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Objects;

/**
 * Records what happened, in the same transaction as the change.
 *
 * <p><b>Propagation MANDATORY is the point of this class.</b> An audit row
 * must commit exactly when its change commits, for the same reason as the
 * notification outbox. If it is written after the commit, a crash in between
 * loses it. If it is written in its own transaction, a rollback of the change
 * leaves a record of something that never happened. MANDATORY makes a call
 * from code with no transaction fail immediately with
 * IllegalTransactionStateException, instead of quietly causing one of those.
 *
 * <p>Each use case calls this explicitly, rather than going through an aspect
 * or a Hibernate listener. Only the use case knows the intent: "canceled by
 * dunning" and "canceled by an admin" are the same row update. An explicit
 * call can also be found with a search.
 *
 * <p>{@code data} is for context a reader needs, never for secrets: no
 * passwords and no payment-method tokens.
 */
@Service
public class AuditService {

    private final AuditEventRepository events;
    private final ObjectMapper objectMapper;

    public AuditService(AuditEventRepository events, ObjectMapper objectMapper) {
        this.events = events;
        this.objectMapper = objectMapper;
    }

    /** Records an event in the current tenant. Used by every tenant-side use case. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditEventType type, Object entityId, Map<String, ?> data) {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant in context to record " + type + " under");
        }
        record(tenantId, type, entityId, data);
    }

    /**
     * Records an event under a tenant named explicitly. Used by platform
     * operations, which act on a tenant from outside it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String tenantId, AuditEventType type, Object entityId, Map<String, ?> data) {
        Objects.requireNonNull(tenantId, "tenantId");
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        AuditActors.requireActorMayRecordFor(authentication, tenantId);
        append(tenantId, AuditActors.from(authentication), type, entityId, data);
    }

    /**
     * Records an event in the current tenant as SYSTEM, whoever is
     * authenticated on this thread.
     *
     * <p>This is for outcomes the platform decides, not a person. A payment
     * settles when the provider reports it, and dunning reacts to that. With
     * the fake gateway both happen inside the request of the user who started
     * the payment. With Stripe they happen in an unauthenticated webhook. If
     * the actor came from the security context, the same outcome would be
     * logged as USER under one provider and SYSTEM under the other. The fake
     * is meant to be the same shape as the real thing, and that includes its
     * audit trail.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordSystem(AuditEventType type, Object entityId, Map<String, ?> data) {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant in context to record " + type + " under");
        }
        append(tenantId, AuditActor.SYSTEM, type, entityId, data);
    }

    private void append(String tenantId, AuditActor actor, AuditEventType type, Object entityId, Map<String, ?> data) {
        Objects.requireNonNull(entityId, "entityId");
        AuditEventEntity event = new AuditEventEntity();
        event.setTenantId(tenantId);
        event.setActorType(actor.type());
        event.setActorId(actor.id());
        event.setType(type);
        event.setEntityType(type.entityType());
        event.setEntityId(entityId.toString());
        event.setData(data == null || data.isEmpty() ? null : toJson(data));
        event.setRequestId(MDC.get(MdcKeys.REQUEST_ID));
        events.append(event);
    }

    /**
     * Newest first. Filtering needs both halves: an entity type with no id
     * would be "every subscription event", which the timeline already shows.
     */
    @Transactional(readOnly = true)
    public Page<AuditEventEntity> list(
            String tenantId, AuditEntityType entityType, String entityId, Pageable pageable) {
        if (entityType != null && entityId != null) {
            return events.findByTenantIdAndEntity(tenantId, entityType, entityId, pageable);
        }
        return events.findByTenantId(tenantId, pageable);
    }

    private String toJson(Map<String, ?> data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            // Callers pass maps of strings, numbers and enums. Failing to
            // serialize one is a programming error, not a runtime condition.
            throw new IllegalStateException("Failed to serialize audit event data", e);
        }
    }
}
