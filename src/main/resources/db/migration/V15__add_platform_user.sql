-- The platform-level principal: someone who operates the service itself
-- (provisions tenants, later reads metrics) rather than acting inside one
-- tenant. Their tokens carry no tenant_id claim at all.
--
-- A separate table rather than app_user with a nullable tenant_id, for two
-- reasons:
--
-- 1. A nullable tenant_id turns "forgot to set the tenant" into "is a
--    platform administrator". Every bug that writes an app_user without a
--    tenant would mint a cross-tenant principal. Two tables make the two
--    kinds of account impossible to confuse by accident.
-- 2. uk_app_user_tenant_email would stop meaning anything for these rows:
--    NULLs never collide in a Postgres unique constraint, so any number of
--    platform users could share one email.
--
-- No role table either. A platform user has exactly one role today
-- (PLATFORM_ADMIN, issued in the token's roles claim); a join table for a
-- one-value vocabulary is ceremony. It becomes worth adding the day a
-- second platform role (read-only operator, say) exists.
--
-- Not tenant-owned, so no tenant_id and no cascade from tenant: deleting a
-- tenant must never take the people who administer the platform with it.
CREATE TABLE platform_user (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    email         text        NOT NULL,
    password_hash text        NOT NULL,           -- bcrypt, same as app_user
    enabled       boolean     NOT NULL DEFAULT true,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_platform_user_email UNIQUE (email)
);
