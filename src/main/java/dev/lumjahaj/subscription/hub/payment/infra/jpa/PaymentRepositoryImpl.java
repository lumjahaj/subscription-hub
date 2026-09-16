package dev.lumjahaj.subscription.hub.payment.infra.jpa;

import dev.lumjahaj.subscription.hub.payment.domain.PaymentRepository;
import org.springframework.stereotype.Repository;

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
}
