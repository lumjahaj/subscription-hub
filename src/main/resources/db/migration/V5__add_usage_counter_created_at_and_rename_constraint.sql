-- usage_counter is missing created_at, unlike every other table.
-- TenantScoped maps created_at as NOT NULL and TenantEntityListener.prePersist
-- always sets it, so a UsageCounterEntity extending TenantScoped would fail
-- schema validation (ddl-auto: validate) at startup without this.
ALTER TABLE usage_counter ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();

-- Same rationale as V4: the entity's @UniqueConstraint(name = "uk_...")
-- needs a real, intentional name to match on rather than Postgres's
-- auto-generated one - which here was already silently truncated to 63
-- characters (usage_counter_tenant_id_subscription_id_meter_key_period_st_key).
-- This name also becomes the ON CONFLICT ON CONSTRAINT target for the
-- metering upsert in UsageCounterJpaRepository.
ALTER TABLE usage_counter RENAME CONSTRAINT usage_counter_tenant_id_subscription_id_meter_key_period_st_key TO uk_usage_counter_tenant_sub_meter_period;
