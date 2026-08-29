package dev.lumjahaj.subscription.hub.usage.infra.jpa;

import dev.lumjahaj.subscription.hub.usage.domain.UsageCounterRepository;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class UsageCounterRepositoryImpl implements UsageCounterRepository {

    private final UsageCounterJpaRepository jpaRepository;

    public UsageCounterRepositoryImpl(UsageCounterJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public UsageCounterEntity upsertAndIncrement(
            String tenantId, UUID subscriptionId, String meterKey,
            Instant periodStart, Instant periodEnd, BigDecimal amount
    ) {
        return jpaRepository.upsertAndIncrement(tenantId, subscriptionId, meterKey, periodStart, periodEnd, amount);
    }

    @Override
    public List<UsageCounterEntity> findByTenantIdAndSubscriptionId(String tenantId, UUID subscriptionId) {
        return jpaRepository.findByTenantIdAndSubscriptionId(tenantId, subscriptionId);
    }

    @Override
    public List<UsageCounterEntity> findByTenantIdAndSubscriptionIdAndPeriodStart(
            String tenantId, UUID subscriptionId, Instant periodStart) {
        return jpaRepository.findByTenantIdAndSubscriptionIdAndPeriodStart(tenantId, subscriptionId, periodStart);
    }
}
