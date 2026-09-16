package dev.lumjahaj.subscription.hub.payment.infra.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentJpaRepository extends JpaRepository<PaymentEntity, UUID> {

    Optional<PaymentEntity> findByTenantIdAndId(String tenantId, UUID id);

    Optional<PaymentEntity> findByTenantIdAndIdempotencyKey(String tenantId, String idempotencyKey);

    List<PaymentEntity> findByTenantIdAndInvoiceIdOrderByCreatedAtAsc(String tenantId, UUID invoiceId);
}
