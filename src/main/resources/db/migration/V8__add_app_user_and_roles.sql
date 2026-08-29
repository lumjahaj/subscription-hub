-- Adds the login identity this project has never had. Until now the
-- tenant came from an unverified X-Tenant-Id header, so any caller could
-- be any tenant; from here a caller authenticates and the tenant is read
-- from a signed JWT claim instead.
--
-- Named app_user, not user, because "user" is a RESERVED word in
-- Postgres - it is a niladic function returning the current database
-- user, so CREATE TABLE user fails with a syntax error and
-- pg_get_keywords() reports it as reserved. The alternative is quoting
-- "user" in every migration, every native query and in
-- @Table(name = "\"user\""), where it also becomes case-sensitive.
-- Every other table stays singular; "order" and "group" are the only
-- other reserved words and neither belongs in a billing domain, so this
-- is the one place the naming convention needs an escape hatch.
--
-- It also draws a line the domain needs anyway: `customer` is who a
-- tenant bills, `app_user` is who logs in to the API. Different people,
-- different tables.

CREATE TABLE app_user (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     varchar(64) NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    email         text        NOT NULL,
    password_hash text        NOT NULL,           -- bcrypt, never a plaintext or reversible form
    enabled       boolean     NOT NULL DEFAULT true,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_app_user_tenant_email UNIQUE (tenant_id, email)
);

-- Unique per (tenant, email), not globally: the same person may hold an
-- account in two tenants, which is why logging in takes a tenant as well
-- as an email. Mirrors uk_customer_tenant_email.
--
-- Constraint named uk_* from the start. V4, V5 and V6 exist only because
-- earlier tables let Postgres auto-generate these names and the entities
-- then disagreed with the database.

-- Roles as a join table rather than a delimited column on app_user.
-- Deliberately NOT a native Postgres enum type like subscription_status
-- or invoice_status: those model domain state machines, whereas this is a
-- controlled vocabulary that grows as the API grows. Same reasoning as
-- invoice_line.kind, which is varchar(16) for the same reason - and it
-- keeps the entity on a plain @Enumerated(EnumType.STRING) instead of the
-- three-annotation NAMED_ENUM combo.
--
-- The CHECK still keeps the database honest about which values are legal,
-- following V3's chk_* naming.
CREATE TABLE app_user_role (
    user_id uuid        NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    role    varchar(32) NOT NULL,
    PRIMARY KEY (user_id, role),
    CONSTRAINT chk_app_user_role_value CHECK (role IN ('ADMIN', 'BILLING', 'SUPPORT', 'USER'))
);

-- No tenant_id on app_user_role and it does not extend TenantScoped: it
-- is owned by an app_user row that is already tenant-scoped, and the
-- cascade above ties its lifetime to that row.

-- ========== SEED (LOCAL DEV ONLY) ==========
-- One ADMIN per seeded tenant, mirroring how V1 seeds the acme and demo
-- tenants themselves. The password is "subscriptionhub", matching the
-- other local dev credentials in .env.example.
--
-- These are development fixtures with a publicly known password, exactly
-- like the acme/demo tenants they belong to. A real deployment must not
-- run with them: see the "known gaps" section of CLAUDE.md. Recorded here
-- rather than left implicit because a seeded admin is the kind of thing
-- that quietly survives into an environment it shouldn't.
WITH seeded_admins AS (
    INSERT INTO app_user (tenant_id, email, password_hash)
    VALUES
        ('acme', 'admin@acme.test', '$2a$10$weWhaGYAMz9Fzc9M.xikWefRTaxBFywEYnMyYYZYz/LTSnaeP8gci'),
        ('demo', 'admin@demo.test', '$2a$10$weWhaGYAMz9Fzc9M.xikWefRTaxBFywEYnMyYYZYz/LTSnaeP8gci')
    ON CONFLICT ON CONSTRAINT uk_app_user_tenant_email DO NOTHING
    RETURNING id
)
INSERT INTO app_user_role (user_id, role)
SELECT id, 'ADMIN' FROM seeded_admins
ON CONFLICT DO NOTHING;
