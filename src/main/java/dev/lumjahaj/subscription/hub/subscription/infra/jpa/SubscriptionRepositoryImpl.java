package dev.lumjahaj.subscription.hub.subscription.infra.jpa;

import dev.lumjahaj.subscription.hub.subscription.domain.SubscriptionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class SubscriptionRepositoryImpl implements SubscriptionRepository {

    private final SubscriptionJpaRepository jpaRepository;

    public SubscriptionRepositoryImpl(SubscriptionJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public SubscriptionEntity save(SubscriptionEntity subscription) {
        return jpaRepository.save(subscription);
    }

    @Override
    public Page<SubscriptionEntity> findByTenantId(String tenantId, Pageable pageable) {
        return jpaRepository.findByTenantId(tenantId, pageable);
    }

    @Override
    public Page<SubscriptionEntity> findByTenantIdAndCustomerId(String tenantId, UUID customerId, Pageable pageable) {
        return jpaRepository.findByTenantIdAndCustomerId(tenantId, customerId, pageable);
    }

    @Override
    public Optional<SubscriptionEntity> findByTenantIdAndId(String tenantId, UUID id) {
        return jpaRepository.findByTenantIdAndId(tenantId, id);
    }
}
