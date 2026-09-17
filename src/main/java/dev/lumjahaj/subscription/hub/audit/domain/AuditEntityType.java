package dev.lumjahaj.subscription.hub.audit.domain;

/**
 * The kinds of record an audit event can be about. Stored as
 * audit_event.entity_type and used as the filter on the read endpoints.
 */
public enum AuditEntityType {
    TENANT,
    PRODUCT,
    PLAN,
    PLAN_ENTITLEMENT,
    CUSTOMER,
    SUBSCRIPTION,
    INVOICE,
    PAYMENT
}
