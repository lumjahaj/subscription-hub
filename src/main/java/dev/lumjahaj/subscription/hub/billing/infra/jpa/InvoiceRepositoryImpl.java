package dev.lumjahaj.subscription.hub.billing.infra.jpa;

import dev.lumjahaj.subscription.hub.billing.domain.InvoiceRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.time.Instant;
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
    public Page<InvoiceEntity> findByTenantId(String tenantId, Pageable pageable) {
        return jpaRepository.findByTenantId(tenantId, pageable);
    }

    @Override
    public Page<InvoiceEntity> findByTenantIdAndSubscriptionId(String tenantId, UUID subscriptionId, Pageable pageable) {
        return jpaRepository.findByTenantIdAndSubscriptionId(tenantId, subscriptionId, pageable);
    }

    @Override
    public long allocateNextNumber(String tenantId) {
        return jpaRepository.allocateNextNumber(tenantId);
    }
}
