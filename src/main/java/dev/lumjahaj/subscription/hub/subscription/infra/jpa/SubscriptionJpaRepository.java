package dev.lumjahaj.subscription.hub.subscription.infra.jpa;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SubscriptionJpaRepository extends JpaRepository<SubscriptionEntity, UUID> {
    Page<SubscriptionEntity> findByTenantId(String tenantId, Pageable pageable);
    Page<SubscriptionEntity> findByTenantIdAndCustomerId(String tenantId, UUID customerId, Pageable pageable);
    Optional<SubscriptionEntity> findByTenantIdAndId(String tenantId, UUID id);
}
