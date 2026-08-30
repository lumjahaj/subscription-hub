package dev.lumjahaj.subscription.hub.auth.domain;

/**
 * Deliberately a plain varchar column with a CHECK constraint rather than
 * a native Postgres enum (see V8) — roles are a controlled vocabulary
 * that grows as the API grows, not a domain state machine like
 * subscription_status. Same call invoice_line.kind made, and it keeps the
 * entity on a plain @Enumerated(EnumType.STRING) instead of the
 * three-annotation NAMED_ENUM combo.
 *
 * Adding a value here also requires widening chk_app_user_role_value in a
 * migration — intentional, so the database never holds a role the
 * application cannot interpret.
 */
public enum Role {
    ADMIN,
    BILLING,
    SUPPORT,
    USER
}
