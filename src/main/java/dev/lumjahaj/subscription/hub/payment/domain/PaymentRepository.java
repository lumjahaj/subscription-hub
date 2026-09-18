package dev.lumjahaj.subscription.hub.payment.domain;

import dev.lumjahaj.subscription.hub.payment.infra.jpa.PaymentEntity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository {

    PaymentEntity save(PaymentEntity payment);

    /**
     * Flushes immediately, so a violation of the in-flight partial unique
     * index or the idempotency-key constraint surfaces inside the caller's
     * transaction callback rather than at commit.
     */
    PaymentEntity saveAndFlush(PaymentEntity payment);

    Optional<PaymentEntity> findByTenantIdAndId(String tenantId, UUID id);

    Optional<PaymentEntity> findByTenantIdAndIdempotencyKey(String tenantId, String idempotencyKey);

    List<PaymentEntity> findByTenantIdAndInvoiceId(String tenantId, UUID invoiceId);

    /**
     * Payments still PENDING since before {@code cutoff}, oldest first — the
     * ones no provider event ever settled. Each one holds its invoice's slot
     * in ux_payment_invoice_in_flight_or_succeeded, so dunning skips that
     * invoice for as long as the row sits here.
     *
     * Bounded by {@code limit} because a provider outage can leave a whole
     * run's worth at once, and the caller talks to the provider once per row.
     */
    List<PaymentEntity> findPendingOlderThan(String tenantId, Instant cutoff, int limit);

    /**
     * Seconds since the oldest PENDING payment was created, across active
     * tenants, or 0 when there is none. Cross-tenant on purpose: it feeds a
     * gauge during a metrics scrape, which has no tenant.
     */
    double oldestPendingAgeSecondsAcrossActiveTenants();
}
