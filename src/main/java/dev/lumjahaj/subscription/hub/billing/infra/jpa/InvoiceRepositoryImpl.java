package dev.lumjahaj.subscription.hub.billing.infra.jpa;

import dev.lumjahaj.subscription.hub.billing.domain.InvoiceRepository;
import dev.lumjahaj.subscription.hub.billing.domain.InvoiceStatus;
import org.hibernate.Hibernate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class InvoiceRepositoryImpl implements InvoiceRepository {

    private final InvoiceJpaRepository jpaRepository;

    public InvoiceRepositoryImpl(InvoiceJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public InvoiceEntity save(InvoiceEntity invoice) {
        return jpaRepository.save(invoice);
    }

    @Override
    public Optional<InvoiceEntity> findByTenantIdAndId(String tenantId, UUID id) {
        return jpaRepository.findByTenantIdAndId(tenantId, id);
    }

    @Override
    public Optional<InvoiceEntity> findByTenantIdAndSubscriptionIdAndPeriodStart(
            String tenantId, UUID subscriptionId, Instant periodStart) {
        return jpaRepository.findByTenantIdAndSubscriptionIdAndPeriodStart(tenantId, subscriptionId, periodStart);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<InvoiceEntity> findByTenantId(String tenantId, Pageable pageable) {
        return withLinesLoaded(jpaRepository.findByTenantId(tenantId, pageable));
    }

    @Override
    public List<InvoiceEntity> findByTenantIdAndStatus(String tenantId, InvoiceStatus status) {
        return jpaRepository.findByTenantIdAndStatus(tenantId, status);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<InvoiceEntity> findByTenantIdAndSubscriptionId(String tenantId, UUID subscriptionId, Pageable pageable) {
        return withLinesLoaded(jpaRepository.findByTenantIdAndSubscriptionId(tenantId, subscriptionId, pageable));
    }

    /**
     * Loads the lines of a page of invoices before the transaction ends, since
     * InvoiceMapper reads them in the controller.
     *
     * Not an @EntityGraph like findByTenantIdAndId: a collection fetch cannot
     * be combined with a page's LIMIT/OFFSET, so Hibernate would load every
     * invoice for the tenant and paginate in memory (HHH90003004). Touching one
     * invoice's lines instead lets InvoiceEntity.lines' @BatchSize load the
     * whole page's lines in one extra IN query. That batch loading only ever
     * worked because open-in-view kept the session alive past the controller;
     * this transaction is what keeps it honest without it.
     *
     * The first adapter method here that is more than a delegation - the seam
     * CLAUDE.md §3 says the repository trio exists for.
     */
    private static Page<InvoiceEntity> withLinesLoaded(Page<InvoiceEntity> page) {
        page.forEach(invoice -> Hibernate.initialize(invoice.getLines()));
        return page;
    }

    @Override
    public long allocateNextNumber(String tenantId) {
        return jpaRepository.allocateNextNumber(tenantId);
    }
}
