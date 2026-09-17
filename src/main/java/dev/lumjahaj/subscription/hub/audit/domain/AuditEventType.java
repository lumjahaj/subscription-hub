package dev.lumjahaj.subscription.hub.audit.domain;

/**
 * What happened. Each type names the kind of record it is about, so a
 * caller passes only the record's id and cannot file a subscription
 * cancellation under an invoice.
 *
 * <p>Deliberately not audited: renewals and usage increments (high volume,
 * and fully derivable - the subscription's periods and the usage counters
 * are already the record), reads, and logins. A failed login for an
 * unknown tenant could not even satisfy audit_event's tenant foreign key,
 * and authentication events belong in logs and metrics.
 */
public enum AuditEventType {

    TENANT_PROVISIONED(AuditEntityType.TENANT),
    TENANT_DEACTIVATED(AuditEntityType.TENANT),
    TENANT_ACTIVATED(AuditEntityType.TENANT),

    PRODUCT_CREATED(AuditEntityType.PRODUCT),
    PLAN_CREATED(AuditEntityType.PLAN),
    PLAN_ENTITLEMENT_CREATED(AuditEntityType.PLAN_ENTITLEMENT),

    CUSTOMER_CREATED(AuditEntityType.CUSTOMER),
    PAYMENT_METHOD_SET(AuditEntityType.CUSTOMER),
    PAYMENT_METHOD_REMOVED(AuditEntityType.CUSTOMER),

    SUBSCRIPTION_CREATED(AuditEntityType.SUBSCRIPTION),
    SUBSCRIPTION_CANCELED(AuditEntityType.SUBSCRIPTION),
    SUBSCRIPTION_PAUSED(AuditEntityType.SUBSCRIPTION),
    SUBSCRIPTION_RESUMED(AuditEntityType.SUBSCRIPTION),
    SUBSCRIPTION_PAST_DUE(AuditEntityType.SUBSCRIPTION),
    SUBSCRIPTION_RECOVERED(AuditEntityType.SUBSCRIPTION),

    INVOICE_ISSUED(AuditEntityType.INVOICE),
    INVOICE_PAID(AuditEntityType.INVOICE),
    INVOICE_UNCOLLECTIBLE(AuditEntityType.INVOICE),

    PAYMENT_SUCCEEDED(AuditEntityType.PAYMENT),
    PAYMENT_FAILED(AuditEntityType.PAYMENT);

    private final AuditEntityType entityType;

    AuditEventType(AuditEntityType entityType) {
        this.entityType = entityType;
    }

    public AuditEntityType entityType() {
        return entityType;
    }
}
