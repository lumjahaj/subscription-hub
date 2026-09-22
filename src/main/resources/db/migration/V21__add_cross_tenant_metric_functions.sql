-- The two deliberately cross-tenant reads, as functions the application may
-- call but whose body runs as the owner.
--
-- Both back Prometheus gauges. A scrape has no tenant - it is not a request -
-- and each returns one aggregate number and no rows, over every active tenant.
-- Once row-level security is enabled they would read 0 instead: not an error,
-- not a failure, just a number that is quietly wrong. That is the worst
-- possible outcome for a metric, and this project has already paid for it once
-- (JobMetrics tagged a meter "job", every test passed, and only a live scrape
-- showed the alerts matched nothing).
--
-- So they need a way past the policies, and the question is how narrow it can
-- be made. SECURITY DEFINER runs the body as the function's owner, which is
-- the role that owns the tables and is therefore exempt from the policies
-- (they are not FORCEd - see V22). The escape hatch is then two named database
-- objects: greppable here, listable with \df+, and revocable with one REVOKE.
--
-- The alternatives were worse. A sentinel tenant value permitted by the policy
-- would be reachable by anything that can set a string, and no test could see
-- a string literal. A second connection as the owner would need its own pool,
-- and "who may use it" becomes a question about bean wiring rather than about
-- a grant.
--
-- SET search_path is not optional on a SECURITY DEFINER function. Without it
-- the caller chooses where "notification" and "tenant" resolve, and can point
-- them at objects of their own that then execute as the owner. pg_catalog
-- first so built-ins cannot be shadowed either.
--
-- Both are STABLE, not IMMUTABLE: they read tables and call now().

CREATE FUNCTION notification_outbox_oldest_age_seconds(p_status text)
    RETURNS double precision
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
    -- Age from the database's clock, the same one that wrote created_at, so
    -- application/database clock skew cannot distort it. Inactive tenants are
    -- excluded because their rows are held back on purpose (the relay skips
    -- them), and counting that as a stuck outbox would be a false alarm.
    -- coalesce so an empty outbox reads 0 rather than NULL.
    SELECT cast(coalesce(extract(epoch from (now() - min(n.created_at))), 0) as double precision)
      FROM notification n
      JOIN tenant t ON t.id = n.tenant_id
     WHERE n.status = p_status
       AND t.active;
$$;

CREATE FUNCTION payment_oldest_pending_age_seconds()
    RETURNS double precision
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
    -- PENDING is written literally rather than taken as a parameter:
    -- payment.status is a Postgres enum (payment_status, V11), so a text
    -- parameter would need an explicit cast, and there is only one status
    -- worth gauging.
    SELECT cast(coalesce(extract(epoch from (now() - min(p.created_at))), 0) as double precision)
      FROM payment p
      JOIN tenant t ON t.id = p.tenant_id
     WHERE p.status = 'PENDING'
       AND t.active;
$$;

-- PUBLIC gets EXECUTE on a new function by default, which for a SECURITY
-- DEFINER function means anyone who can connect. Taken back first, then given
-- to exactly the one role that needs it.
REVOKE EXECUTE ON FUNCTION notification_outbox_oldest_age_seconds(text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION payment_oldest_pending_age_seconds() FROM PUBLIC;

GRANT EXECUTE ON FUNCTION notification_outbox_oldest_age_seconds(text) TO subscription_hub_app;
GRANT EXECUTE ON FUNCTION payment_oldest_pending_age_seconds() TO subscription_hub_app;
