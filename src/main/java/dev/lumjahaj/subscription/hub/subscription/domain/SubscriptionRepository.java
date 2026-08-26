package dev.lumjahaj.subscription.hub.subscription.domain;

import dev.lumjahaj.subscription.hub.subscription.infra.jpa.SubscriptionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.util.UUID;

public interface SubscriptionRepository {
    SubscriptionEntity save(SubscriptionEntity subscription);
    Page<SubscriptionEntity> findByTenantId(String tenantId, Pageable pageable);
    Page<SubscriptionEntity> findByTenantIdAndCustomerId(String tenantId, UUID customerId, Pageable pageable);
    Optional<SubscriptionEntity> findByTenantIdAndId(String tenantId, UUID id);
}
