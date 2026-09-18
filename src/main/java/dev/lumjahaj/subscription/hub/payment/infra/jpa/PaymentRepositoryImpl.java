package dev.lumjahaj.subscription.hub.payment.infra.jpa;

import dev.lumjahaj.subscription.hub.payment.domain.PaymentRepository;
import dev.lumjahaj.subscription.hub.payment.domain.PaymentStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PaymentRepositoryImpl implements PaymentRepository {

    private final PaymentJpaRepository jpaRepository;

    public PaymentRepositoryImpl(PaymentJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public PaymentEntity save(PaymentEntity payment) {
        return jpaRepository.save(payment);
    }

    @Override
    public PaymentEntity saveAndFlush(PaymentEntity payment) {
        return jpaRepository.saveAndFlush(payment);
    }

    @Override
    public Optional<PaymentEntity> findByTenantIdAndId(String tenantId, UUID id) {
        return jpaRepository.findByTenantIdAndId(tenantId, id);
    }

    @Override
    public Optional<PaymentEntity> findByTenantIdAndIdempotencyKey(String tenantId, String idempotencyKey) {
        return jpaRepository.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey);
    }

    // Oldest first, so a list reads as the history of attempts on the invoice.
    @Override
    public List<PaymentEntity> findByTenantIdAndInvoiceId(String tenantId, UUID invoiceId) {
        return jpaRepository.findByTenantIdAndInvoiceIdOrderByCreatedAtAsc(tenantId, invoiceId);
    }

    // PENDING and the page size stay here rather than in the port: which
    // status counts as unsettled is this adapter's business, and Pageable is
    // exactly the Spring Data surface the domain contract exists to keep out.
    @Override
    public List<PaymentEntity> findPendingOlderThan(String tenantId, Instant cutoff, int limit) {
        return jpaRepository.findByTenantIdAndStatusAndCreatedAtLessThanOrderByCreatedAtAsc(
                tenantId, PaymentStatus.PENDING, cutoff, PageRequest.of(0, limit));
    }

    @Override
    public double oldestPendingAgeSecondsAcrossActiveTenants() {
        return jpaRepository.oldestPendingAgeSecondsAcrossActiveTenants();
    }
}
