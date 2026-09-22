-- Tenant isolation, enforced by the database rather than by the application.
--
-- Until now isolation rested on two things, both inside the application: the
-- convention that every query names the tenant (findByTenantIdAndCode, never
-- findByCode), and Hibernate's @TenantId, which adds the predicate
-- automatically - but only to queries Hibernate builds. A native query, a raw
-- JDBC statement, a reporting tool or a migration all sit outside it. Two of
-- the four native queries in this codebase bind tenant_id by hand precisely
-- because nothing else would.
--
-- These policies apply to every statement the application's connection issues,
-- whichever layer wrote it. The predicate reads a session setting that
-- TenantAwareDataSource writes on every connection borrow.
--
-- current_setting(..., true) rather than current_setting(...): the second
-- argument means "return NULL instead of raising if unset". NULL then makes
-- the predicate NULL, which matches no rows - so an unbound connection reads
-- nothing rather than everything. Fail closed, the same posture as
-- TenantIdentifierResolver's __no_tenant__ sentinel and TenantEntityListener
-- refusing to persist without a tenant.
--
-- Deliberately NOT "ALTER TABLE ... FORCE ROW LEVEL SECURITY". Without FORCE
-- the table owner is exempt, and the owner is what Flyway migrates as. That is
-- what keeps a cross-tenant backfill working: V3's
-- "UPDATE plan SET interval_unit = interval::plan_interval_unit" rewrites
-- every tenant's rows at once, and under FORCE it would silently update none.
-- The application cannot benefit from that exemption, because it connects as
-- subscription_hub_app, which owns nothing and is NOBYPASSRLS.
--
-- Written as a loop over an explicit list rather than as 13 copy-pasted pairs:
-- the list is still right here to read, and every table provably gets the same
-- policy rather than the one that was mistyped.
--
-- Two tenant-owned tables are deliberately absent, both already exceptions to
-- @TenantId for the same underlying reason - the tenant is not known when the
-- work starts:
--   app_user     - a login has no tenant in context; reading this table is
--                  what establishes one.
--   audit_event  - a platform administrator records events under the tenant
--                  being acted on, from a request that has no tenant, and the
--                  event must commit in that change's own transaction.
-- Both follow in their own migration, together with the code changes that make
-- them possible. Also absent, and permanently: tenant and platform_user (they
-- belong to no tenant) and app_user_role (no tenant_id column - it hangs off
-- app_user by user_id).
DO $$
DECLARE
    target text;
BEGIN
    FOREACH target IN ARRAY ARRAY[
        'customer',
        'product',
        'plan',
        'plan_entitlement',
        'subscription',
        'subscription_entitlement_override',
        'usage_counter',
        'invoice',
        'invoice_line',
        'payment',
        'dunning_state',
        'notification',
        -- No entity and no Hibernate query: written only by the native upsert
        -- in InvoiceJpaRepository.allocateNextNumber, which is exactly the
        -- kind of statement @TenantId never covered.
        'invoice_number_sequence'
    ]
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', target);

        -- FOR ALL, so one policy covers SELECT, INSERT, UPDATE and DELETE.
        -- USING filters the rows a statement may see or change; WITH CHECK
        -- refuses rows it would write under another tenant. Both are needed:
        -- USING alone would let a tenant insert a row it then could not read.
        EXECUTE format($policy$
            CREATE POLICY tenant_isolation ON %I
                FOR ALL
                USING (tenant_id = current_setting('app.tenant_id', true))
                WITH CHECK (tenant_id = current_setting('app.tenant_id', true))
        $policy$, target);
    END LOOP;
END
$$;
