package dev.lumjahaj.subscription.hub.usage.domain;

import dev.lumjahaj.subscription.hub.usage.infra.jpa.UsageCounterEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface UsageCounterRepository {

    /**
     * Atomically records a usage increment: creates the counter row for
     * (tenant, subscription, meter, period) if it doesn't exist yet, or
     * adds `amount` to the existing one. A plain check-then-save would
     * lose increments under concurrent requests for the same meter -
     * this is a read-modify-write on a running total, not a uniqueness
     * check, so the fix isn't "catch the race", it's "make the write
     * atomic". See UsageCounterJpaRepository for the upsert.
     */
    UsageCounterEntity upsertAndIncrement(
            String tenantId, UUID subscriptionId, String meterKey,
            Instant periodStart, Instant periodEnd, BigDecimal amount);

    List<UsageCounterEntity> findByTenantIdAndSubscriptionId(String tenantId, UUID subscriptionId);
}
