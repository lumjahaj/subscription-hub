-- What the application's runtime role may do.
--
-- The role itself is infrastructure (docker/postgres/init/01-app-role.sh),
-- because it carries a password and CLAUDE.md §5 keeps credentials out of
-- db/migration, which runs in every environment. What it is *allowed to do*
-- is schema, so it belongs here, alongside the row-level security policies
-- that follow in a later migration.
--
-- Nothing connects as this role yet; the application still uses the owner.
-- This migration only makes the role usable.

-- Fail with something a reader can act on. Without this the first symptom is
-- "role subscription_hub_app does not exist" from a GRANT, several lines into
-- a stack trace, with no hint that the cause is an init script that only runs
-- against an empty data directory.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'subscription_hub_app') THEN
        RAISE EXCEPTION
            'Role "subscription_hub_app" does not exist. It is created by '
            'docker/postgres/init/01-app-role.sh, which the Postgres image runs only '
            'when the data directory is first initialised - so an existing local '
            'database never got it. See README, "Upgrading an existing local database".';
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO subscription_hub_app;

-- DML only, deliberately: no TRUNCATE, no REFERENCES, no TRIGGER, and no DDL.
-- The application reads and writes rows; it never changes the shape of the
-- schema. That is the owner's job, and the owner is Flyway.
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public
    TO subscription_hub_app;

-- Flyway's own bookkeeping is not the application's business. It is caught by
-- the blanket grant above because it lives in the same schema, so it is taken
-- back explicitly rather than by enumerating every other table here and having
-- to remember this file on every future migration.
REVOKE ALL ON flyway_schema_history FROM subscription_hub_app;

-- Tables added by later migrations. Default privileges apply to objects created
-- by the role that runs this statement, which is the owner - and the owner is
-- also what Flyway migrates as, so every future migration's tables are covered
-- without anyone having to remember a GRANT.
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO subscription_hub_app;

-- There is not a single sequence in this schema today: primary keys are UUIDs
-- or assigned slugs, and invoice numbering is a counter table
-- (invoice_number_sequence) precisely so it can be tenant-scoped. This line is
-- here so that the day someone adds a bigserial, it works instead of failing
-- with "permission denied for sequence" a long way from the cause. It grants
-- nothing today.
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO subscription_hub_app;
