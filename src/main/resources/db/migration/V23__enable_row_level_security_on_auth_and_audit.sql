-- The last two tenant-owned tables, and the ones that mattered most.
--
-- app_user and audit_event are the only entities deliberately excluded from
-- Hibernate's @TenantId (see AppUserEntity and AuditEventEntity). Both were
-- excluded for the same underlying reason: the tenant is not known when the
-- work starts. A login has no tenant in context because reading app_user is
-- what establishes one; a platform administrator records an event under the
-- tenant being acted on, from a request that names no tenant of its own.
--
-- The consequence was that these two had no structural backstop at all. Every
-- other tenant-owned table had @TenantId adding a predicate even if a query
-- forgot; here, a single repository method written without tenantId would leak
-- across tenants with nothing to catch it. That is the gap this whole change
-- exists to close, so leaving these two out would have meant hardening the
-- tables that were already protected twice and skipping the ones protected
-- once.
--
-- What makes it possible now is that row-level security reads a setting on the
-- connection rather than a Hibernate session attribute, so the tenant only has
-- to be established before the transaction opens - not before Hibernate builds
-- its query. TenantContext.callAs at the three entry points does that:
-- AuthController (login), and PlatformTenantController (provisioning,
-- activation, and the platform view of a tenant's audit log).
--
-- Same policy as V22, and the same reasoning: FOR ALL with USING and WITH
-- CHECK, current_setting(..., true) so an unbound connection reads nothing,
-- and no FORCE so the owner - which is what Flyway migrates as, and what the
-- seeded logins in db/seed are inserted by - stays exempt.
--
-- app_user_role is not included and has no tenant_id: it hangs off app_user by
-- user_id, so it is reachable only through a row app_user's policy already
-- decided the caller may see.
DO $$
DECLARE
    target text;
BEGIN
    FOREACH target IN ARRAY ARRAY['app_user', 'audit_event']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', target);

        EXECUTE format($policy$
            CREATE POLICY tenant_isolation ON %I
                FOR ALL
                USING (tenant_id = current_setting('app.tenant_id', true))
                WITH CHECK (tenant_id = current_setting('app.tenant_id', true))
        $policy$, target);
    END LOOP;
END
$$;
