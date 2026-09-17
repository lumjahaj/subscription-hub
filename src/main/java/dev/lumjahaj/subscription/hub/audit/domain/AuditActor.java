package dev.lumjahaj.subscription.hub.audit.domain;

/**
 * Who caused an audit event. {@code id} is the token subject (a user or
 * platform-user UUID) and is null only for {@link ActorType#SYSTEM}.
 */
public record AuditActor(ActorType type, String id) {

    public static final AuditActor SYSTEM = new AuditActor(ActorType.SYSTEM, null);
}
