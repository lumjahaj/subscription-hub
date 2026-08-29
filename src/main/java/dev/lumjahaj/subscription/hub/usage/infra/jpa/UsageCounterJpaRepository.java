package dev.lumjahaj.subscription.hub.usage.infra.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface UsageCounterJpaRepository extends JpaRepository<UsageCounterEntity, UUID> {

    List<UsageCounterEntity> findByTenantIdAndSubscriptionId(String tenantId, UUID subscriptionId);

    /**
     * The project's first native/nativeQuery = true query - and the one
     * place @TenantId's automatic "tenant_id =" predicate (see
     * TenantScoped) does NOT apply, per CLAUDE.md §4. tenantId is bound
     * explicitly here and is also part of the ON CONFLICT target, so a
     * caller that gets the tenant wrong creates a wrong-tenant row rather
     * than silently colliding with another tenant's counter.
     *
     * ON CONFLICT ON CONSTRAINT targets uk_usage_counter_tenant_sub_meter_period
     * (see V5 migration - the constraint enforcing one row per tenant +
     * subscription + meter + period). Not @Modifying: the RETURNING
     * clause makes this a row-returning statement, and Spring Data maps
     * the returned columns onto UsageCounterEntity the same way a SELECT
     * native query would.
     */
    @Query(value = """
            INSERT INTO usage_counter
                (tenant_id, subscription_id, meter_key, period_start, period_end, amount, created_at, updated_at)
            VALUES
                (:tenantId, :subscriptionId, :meterKey, :periodStart, :periodEnd, :amount, now(), now())
            ON CONFLICT ON CONSTRAINT uk_usage_counter_tenant_sub_meter_period
            DO UPDATE SET
                amount = usage_counter.amount + EXCLUDED.amount,
                updated_at = now()
            RETURNING *
            """, nativeQuery = true)
    UsageCounterEntity upsertAndIncrement(
            @Param("tenantId") String tenantId,
            @Param("subscriptionId") UUID subscriptionId,
            @Param("meterKey") String meterKey,
            @Param("periodStart") Instant periodStart,
            @Param("periodEnd") Instant periodEnd,
            @Param("amount") BigDecimal amount);
}
