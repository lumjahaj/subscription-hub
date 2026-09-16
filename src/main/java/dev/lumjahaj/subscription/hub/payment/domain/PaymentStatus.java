package dev.lumjahaj.subscription.hub.payment.domain;

/**
 * Must match the Postgres payment_status enum exactly (V11).
 *
 * PENDING is the only state the API writes. SUCCEEDED and FAILED are
 * reached only through PaymentSettlementService, from a provider event.
 */
public enum PaymentStatus {
    PENDING,
    SUCCEEDED,
    FAILED
}
