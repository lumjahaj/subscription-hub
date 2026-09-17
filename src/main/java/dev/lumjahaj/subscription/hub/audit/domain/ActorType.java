package dev.lumjahaj.subscription.hub.audit.domain;

/**
 * What kind of principal caused an audit event. Mirrors
 * chk_audit_event_actor_type (V16).
 */
public enum ActorType {

    /** A tenant account, acting inside its own tenant. */
    USER,

    /** A platform administrator, acting on a tenant from outside it. */
    PLATFORM_ADMIN,

    /**
     * No authenticated principal: a scheduled job, or a provider webhook
     * that authenticates by signature rather than by token.
     */
    SYSTEM
}
