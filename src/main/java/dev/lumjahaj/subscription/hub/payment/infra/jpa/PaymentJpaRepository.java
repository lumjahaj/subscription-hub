package dev.lumjahaj.subscription.hub.payment.infra.jpa;

import dev.lumjahaj.subscription.hub.payment.domain.PaymentStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentJpaRepository extends JpaRepository<PaymentEntity, UUID> {

    Optional<PaymentEntity> findByTenantIdAndId(String tenantId, UUID id);

    Optional<PaymentEntity> findByTenantIdAndIdempotencyKey(String tenantId, String idempotencyKey);

    List<PaymentEntity> findByTenantIdAndInvoiceIdOrderByCreatedAtAsc(String tenantId, UUID invoiceId);

    /** Backed by the partial index idx_payment_pending_created (V18). */
    List<PaymentEntity> findByTenantIdAndStatusAndCreatedAtLessThanOrderByCreatedAtAsc(
            String tenantId, PaymentStatus status, Instant cutoff, Pageable pageable);

    /**
     * Native, and so outside Hibernate's @TenantId predicate - which here is
     * the point rather than the risk, exactly as for
     * NotificationJpaRepository.oldestAgeSecondsAcrossActiveTenants: a metrics
     * scrape has no tenant, and this aggregates over all of them, returning
     * one number and no rows. Contrast upsertAndIncrement and
     * allocateNextNumber, native for atomicity, which bind tenantId by hand.
     *
     * The aggregate itself lives in a SECURITY DEFINER function (V21), for the
     * same reason as its notification counterpart: being outside @TenantId
     * stopped being enough once the row-level security policies applied to
     * every statement this connection issues, and a gauge that silently reads
     * 0 is worse than one that fails. The function body runs as the table
     * owner, and EXECUTE is granted to exactly one role.
     */
    @Query(value = "select payment_oldest_pending_age_seconds()", nativeQuery = true)
    double oldestPendingAgeSecondsAcrossActiveTenants();
}
