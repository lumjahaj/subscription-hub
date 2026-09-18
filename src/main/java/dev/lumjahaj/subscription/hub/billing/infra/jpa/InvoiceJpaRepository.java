package dev.lumjahaj.subscription.hub.billing.infra.jpa;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import dev.lumjahaj.subscription.hub.billing.domain.InvoiceStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvoiceJpaRepository extends JpaRepository<InvoiceEntity, UUID> {

    @EntityGraph(attributePaths = "lines")
    Optional<InvoiceEntity> findByTenantIdAndId(String tenantId, UUID id);

    Optional<InvoiceEntity> findByTenantIdAndSubscriptionIdAndPeriodStart(
            String tenantId, UUID subscriptionId, Instant periodStart);

    Page<InvoiceEntity> findByTenantId(String tenantId, Pageable pageable);

    List<InvoiceEntity> findByTenantIdAndStatus(String tenantId, InvoiceStatus status);

    Page<InvoiceEntity> findByTenantIdAndSubscriptionId(String tenantId, UUID subscriptionId, Pageable pageable);

    /**
     * Records where an invoice's PDF was stored, and touches nothing else.
     *
     * A targeted UPDATE rather than setting the field on a loaded entity and
     * saving it, for the same reason subscriptions changed to compare-and-set:
     * Hibernate writes every column of a dirty entity at commit, from the
     * snapshot it loaded. Because generatePdf loads the invoice, then makes a
     * slow remote call to the object store, a payment can settle in that
     * window - and saving the entity afterwards wrote the stale OPEN status
     * back over it, reverting a PAID invoice and its paid_at. That is money:
     * the invoice is collected again by dunning. Here the statement names one
     * column, so status and paid_at cannot be collateral damage however stale
     * the caller's view is.
     *
     * The {@code pdfObjectKey is null} guard makes it idempotent too: of two
     * concurrent generations only one changes a row, and the loser is told the
     * PDF already exists instead of both silently claiming success.
     *
     * No clearAutomatically, matching SubscriptionJpaRepository: the caller
     * re-reads through the adapter, which refreshes just this invoice.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update InvoiceEntity i
               set i.pdfObjectKey = :objectKey, i.updatedAt = :now
             where i.tenantId = :tenantId and i.id = :id and i.pdfObjectKey is null
            """)
    int attachPdfObjectKeyIfAbsent(@Param("tenantId") String tenantId, @Param("id") UUID id,
                                   @Param("objectKey") String objectKey, @Param("now") Instant now);

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
