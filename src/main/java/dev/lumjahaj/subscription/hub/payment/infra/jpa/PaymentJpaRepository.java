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
     * Age comes from the database's clock, the same one that wrote created_at,
     * so clock skew between application and database cannot distort it.
     *
     * PENDING is written literally rather than bound: payment.status is a
     * Postgres enum (payment_status, V11), and a bound string parameter would
     * need an explicit cast here. There is only one status worth gauging.
     */
    @Query(value = """
            select cast(coalesce(extract(epoch from (now() - min(p.created_at))), 0) as double precision)
              from payment p
              join tenant t on t.id = p.tenant_id
             where p.status = 'PENDING'
               and t.active
            """, nativeQuery = true)
    double oldestPendingAgeSecondsAcrossActiveTenants();
}
