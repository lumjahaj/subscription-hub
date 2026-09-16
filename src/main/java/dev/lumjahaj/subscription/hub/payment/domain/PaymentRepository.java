package dev.lumjahaj.subscription.hub.payment.domain;

import dev.lumjahaj.subscription.hub.payment.infra.jpa.PaymentEntity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository {

    PaymentEntity save(PaymentEntity payment);

    /**
     * Flushes immediately, so a violation of the in-flight partial unique
     * index or the idempotency-key constraint surfaces inside the caller's
     * transaction callback rather than at commit.
     */
    PaymentEntity saveAndFlush(PaymentEntity payment);

    Optional<PaymentEntity> findByTenantIdAndId(String tenantId, UUID id);

    Optional<PaymentEntity> findByTenantIdAndIdempotencyKey(String tenantId, String idempotencyKey);

    List<PaymentEntity> findByTenantIdAndInvoiceId(String tenantId, UUID invoiceId);
}
