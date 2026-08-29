package dev.lumjahaj.subscription.hub.billing.infra.jpa;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface InvoiceJpaRepository extends JpaRepository<InvoiceEntity, UUID> {

    @EntityGraph(attributePaths = "lines")
    Optional<InvoiceEntity> findByTenantIdAndId(String tenantId, UUID id);

    Optional<InvoiceEntity> findByTenantIdAndSubscriptionIdAndPeriodStart(
            String tenantId, UUID subscriptionId, Instant periodStart);

    Page<InvoiceEntity> findByTenantId(String tenantId, Pageable pageable);

    Page<InvoiceEntity> findByTenantIdAndSubscriptionId(String tenantId, UUID subscriptionId, Pageable pageable);

    /**
     * The project's second native/nativeQuery = true query, for the same
     * reason as the first (UsageCounterJpaRepository.upsertAndIncrement):
     * a read-modify-write on the counter would race two concurrent
     * invoice generations for the same tenant into the same number.
     * @TenantId's automatic predicate does not apply to native SQL, so
     * tenantId is bound explicitly here (CLAUDE.md §4). ON CONFLICT
     * (tenant_id) uses column inference rather than a constraint name,
     * so it doesn't depend on Postgres's PK naming. Not @Modifying — the
     * RETURNING clause makes this row-returning.
     */
    @Query(value = """
            INSERT INTO invoice_number_sequence (tenant_id, last_value, updated_at)
            VALUES (:tenantId, 1, now())
            ON CONFLICT (tenant_id)
            DO UPDATE SET last_value = invoice_number_sequence.last_value + 1, updated_at = now()
            RETURNING last_value
            """, nativeQuery = true)
    long allocateNextNumber(@Param("tenantId") String tenantId);
}
