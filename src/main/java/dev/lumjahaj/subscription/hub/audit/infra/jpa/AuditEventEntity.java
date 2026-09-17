package dev.lumjahaj.subscription.hub.audit.infra.jpa;

import dev.lumjahaj.subscription.hub.audit.domain.ActorType;
import dev.lumjahaj.subscription.hub.audit.domain.AuditEntityType;
import dev.lumjahaj.subscription.hub.audit.domain.AuditEventType;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * One thing that happened to one record, and who did it.
 *
 * <p><b>The second tenant-owned entity that does not extend TenantScoped</b>,
 * after AppUserEntity, and for a different reason. Some events are recorded
 * by a platform administrator on a tenant's behalf (provisioning,
 * deactivation). A platform request has no TenantContext, so with
 * open-in-view its Hibernate session is pinned to the
 * {@code __no_tenant__} sentinel - and {@code @TenantId} rejects an insert
 * whose tenant differs from the session's. Those events must commit in the
 * same transaction as the tenant change they describe, so the
 * PaymentWebhookService trick of opening a fresh session is not available
 * either. It also saves the platform read endpoint that same session
 * treatment.
 *
 * <p>It is also append-only, so TenantScoped's updated_at would be a column
 * that can never mean anything.
 *
 * <p>Isolation is not weakened in practice: AuditEventRepository has no
 * method that does not take a tenant, and AuditIntegrationTest proves one
 * tenant cannot read another's events. What is lost is the backstop for a
 * future query that forgets the tenant - the same trade AppUserEntity
 * documents.
 */
@Entity
@Table(name = "audit_event")
public class AuditEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // A plain column, not TenantScoped's @TenantId field - see above.
    @Column(name = "tenant_id", nullable = false, length = 64, updatable = false)
    private String tenantId;

    // Plain @Enumerated(STRING): varchar + CHECK in V16, not a native enum.
    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 16, updatable = false)
    private ActorType actorType;

    @Column(name = "actor_id", length = 64, updatable = false)
    private String actorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 64, updatable = false)
    private AuditEventType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 64, updatable = false)
    private AuditEntityType entityType;

    @Column(name = "entity_id", nullable = false, length = 64, updatable = false)
    private String entityId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data", columnDefinition = "jsonb", updatable = false)
    private String data;

    @Column(name = "request_id", length = 64, updatable = false)
    private String requestId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public ActorType getActorType() { return actorType; }
    public void setActorType(ActorType actorType) { this.actorType = actorType; }
    public String getActorId() { return actorId; }
    public void setActorId(String actorId) { this.actorId = actorId; }
    public AuditEventType getType() { return type; }
    public void setType(AuditEventType type) { this.type = type; }
    public AuditEntityType getEntityType() { return entityType; }
    public void setEntityType(AuditEntityType entityType) { this.entityType = entityType; }
    public String getEntityId() { return entityId; }
    public void setEntityId(String entityId) { this.entityId = entityId; }
    public String getData() { return data; }
    public void setData(String data) { this.data = data; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public Instant getCreatedAt() { return createdAt; }
}
