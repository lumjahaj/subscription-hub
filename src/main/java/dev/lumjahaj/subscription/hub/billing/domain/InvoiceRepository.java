package dev.lumjahaj.subscription.hub.billing.domain;

import dev.lumjahaj.subscription.hub.billing.infra.jpa.InvoiceEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvoiceRepository {
    InvoiceEntity save(InvoiceEntity invoice);
    Optional<InvoiceEntity> findByTenantIdAndId(String tenantId, UUID id);
    Optional<InvoiceEntity> findByTenantIdAndSubscriptionIdAndPeriodStart(
            String tenantId, UUID subscriptionId, Instant periodStart);
    Page<InvoiceEntity> findByTenantId(String tenantId, Pageable pageable);

    /**
     * Every invoice in one status — how dunning finds what is still unpaid.
     * Unpaged because the result is bounded by design: an OPEN invoice is
     * either collected or written off as UNCOLLECTIBLE, so the set cannot
     * grow without limit.
     */
    List<InvoiceEntity> findByTenantIdAndStatus(String tenantId, InvoiceStatus status);
    Page<InvoiceEntity> findByTenantIdAndSubscriptionId(String tenantId, UUID subscriptionId, Pageable pageable);

    /**
     * Atomically allocates the next per-tenant invoice number. A read of
     * max(number) followed by an insert races the same way the usage
     * counter's read-modify-write did — two concurrent invoice
     * generations for one tenant would read the same max and collide.
     * See InvoiceJpaRepository.allocateNextNumber for the upsert.
     */
    long allocateNextNumber(String tenantId);
}
