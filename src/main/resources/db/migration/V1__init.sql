-- PostgreSQL 14+ (works on 17). Enable UUIDs.
CREATE EXTENSION IF NOT EXISTS "pgcrypto"; -- for gen_random_uuid()

-- ========== TENANCY ==========
CREATE TABLE tenant (
                        id              varchar(64) PRIMARY KEY,
                        name            text        NOT NULL,
                        active          boolean     NOT NULL DEFAULT true,
                        created_at      timestamptz NOT NULL DEFAULT now(),
                        updated_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_tenant_active ON tenant(active);

-- ========== CUSTOMERS ==========
CREATE TABLE customer (
                          id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
                          tenant_id       varchar(64) NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
                          external_id     text,                         -- optional external mapping
                          email           text        NOT NULL,
                          name            text        NOT NULL,
                          created_at      timestamptz NOT NULL DEFAULT now(),
                          updated_at      timestamptz NOT NULL DEFAULT now(),
                          UNIQUE (tenant_id, email)
);

-- ========== CATALOG ==========
CREATE TABLE product (
                         id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
                         tenant_id       varchar(64) NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
                         code            varchar(64) NOT NULL,
                         name            text        NOT NULL,
                         description     text,
                         created_at      timestamptz NOT NULL DEFAULT now(),
                         updated_at      timestamptz NOT NULL DEFAULT now(),
                         UNIQUE (tenant_id, code)
);

CREATE TABLE plan (
                      id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
                      tenant_id       varchar(64) NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
                      product_id      uuid        NOT NULL REFERENCES product(id) ON DELETE CASCADE,
                      code            varchar(64) NOT NULL,
                      name            text        NOT NULL,
                      interval        varchar(16) NOT NULL,      -- e.g., MONTH, YEAR
                      amount_cents    bigint      NOT NULL,      -- base price in minor units
                      currency        char(3)     NOT NULL DEFAULT 'EUR',
                      trial_days      int         NOT NULL DEFAULT 0,
                      created_at      timestamptz NOT NULL DEFAULT now(),
                      updated_at      timestamptz NOT NULL DEFAULT now(),
                      UNIQUE (tenant_id, code)
);

-- Entitlements belong to a plan; think “features/limits” exposed by the plan
CREATE TABLE plan_entitlement (
                                  id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
                                  tenant_id       varchar(64) NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
                                  plan_id         uuid        NOT NULL REFERENCES plan(id) ON DELETE CASCADE,
                                  key             varchar(64) NOT NULL,      -- e.g., "projects.max"
                                  value_json      jsonb       NOT NULL,      -- flexible: { "limit": 10 } or { "enabled": true }
                                  created_at      timestamptz NOT NULL DEFAULT now(),
                                  updated_at      timestamptz NOT NULL DEFAULT now(),
                                  UNIQUE (tenant_id, plan_id, key)
);

-- ========== SUBSCRIPTIONS ==========
-- simple states for now
CREATE TYPE subscription_status AS ENUM ('TRIALING', 'ACTIVE', 'PAST_DUE', 'CANCELED');

CREATE TABLE subscription (
                              id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
                              tenant_id       varchar(64) NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
                              customer_id     uuid        NOT NULL REFERENCES customer(id) ON DELETE CASCADE,
                              plan_id         uuid        NOT NULL REFERENCES plan(id) ON DELETE RESTRICT,
                              status          subscription_status NOT NULL,
                              start_at        timestamptz NOT NULL DEFAULT now(),
                              current_period_start timestamptz NOT NULL,
                              current_period_end   timestamptz NOT NULL,
                              next_renewal    timestamptz,               -- often = current_period_end
                              cancel_at       timestamptz,
                              canceled_at     timestamptz,
                              created_at      timestamptz NOT NULL DEFAULT now(),
                              updated_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_subscription_next_renewal ON subscription(tenant_id, next_renewal) WHERE next_renewal IS NOT NULL;

-- Per-subscription entitlement overrides (e.g., custom limits)
CREATE TABLE subscription_entitlement_override (
                                                   id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
                                                   tenant_id       varchar(64) NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
                                                   subscription_id uuid        NOT NULL REFERENCES subscription(id) ON DELETE CASCADE,
                                                   key             varchar(64) NOT NULL,
                                                   value_json      jsonb       NOT NULL,
                                                   created_at      timestamptz NOT NULL DEFAULT now(),
                                                   updated_at      timestamptz NOT NULL DEFAULT now(),
                                                   UNIQUE (tenant_id, subscription_id, key)
);

-- ========== USAGE METERING ==========
-- tracks usage per billing period (derived from subscription period)
CREATE TABLE usage_counter (
                               id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
                               tenant_id       varchar(64) NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
                               subscription_id uuid        NOT NULL REFERENCES subscription(id) ON DELETE CASCADE,
                               meter_key       varchar(64) NOT NULL,          -- e.g., "emails.sent"
                               period_start    timestamptz NOT NULL,
                               period_end      timestamptz NOT NULL,
                               amount          numeric(20,6) NOT NULL DEFAULT 0,  -- support fractional usage if needed
                               updated_at      timestamptz NOT NULL DEFAULT now(),
                               UNIQUE (tenant_id, subscription_id, meter_key, period_start)
);

-- ========== BILLING / INVOICES ==========
CREATE TYPE invoice_status AS ENUM ('DRAFT','OPEN','PAID','VOID','UNCOLLECTIBLE');

CREATE TABLE invoice (
                         id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
                         tenant_id       varchar(64) NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
                         subscription_id uuid        NOT NULL REFERENCES subscription(id) ON DELETE SET NULL,
                         customer_id     uuid        NOT NULL REFERENCES customer(id) ON DELETE SET NULL,
                         number          varchar(32) NOT NULL,                -- human-readable sequence per tenant
                         status          invoice_status NOT NULL DEFAULT 'DRAFT',
                         currency        char(3)     NOT NULL DEFAULT 'EUR',
                         total_cents     bigint      NOT NULL DEFAULT 0,
                         issued_at       timestamptz,
                         due_at          timestamptz,
                         pdf_url         text,                                 -- points to object store (MinIO)
                         created_at      timestamptz NOT NULL DEFAULT now(),
                         updated_at      timestamptz NOT NULL DEFAULT now(),
                         UNIQUE (tenant_id, number)
);

CREATE TABLE invoice_line (
                              id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
                              tenant_id       varchar(64) NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
                              invoice_id      uuid        NOT NULL REFERENCES invoice(id) ON DELETE CASCADE,
                              kind            varchar(16) NOT NULL,                 -- BASE | USAGE | DISCOUNT | TAX, etc.
                              description     text        NOT NULL,
                              quantity        numeric(20,6) NOT NULL DEFAULT 1,
                              unit_amount_cents bigint    NOT NULL DEFAULT 0,
                              amount_cents    bigint      NOT NULL DEFAULT 0
);

-- ========== AUDIT ==========
CREATE TABLE audit_event (
                             id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
                             tenant_id       varchar(64) NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
                             actor           text,              -- username, client id, system, etc.
                             type            varchar(64) NOT NULL,   -- e.g., "PLAN_CREATED", "SUBSCRIPTION_ACTIVATED"
                             entity_type     varchar(64),
                             entity_id       text,
                             data            jsonb,
                             created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_tenant_created ON audit_event(tenant_id, created_at DESC);

-- ========== SEED MINIMAL TENANTS (optional for local dev) ==========
INSERT INTO tenant (id, name, active) VALUES
    ('acme', 'Acme Inc.', true)
    ON CONFLICT (id) DO NOTHING;

INSERT INTO tenant (id, name, active) VALUES
    ('demo', 'Demo Tenant', true)
    ON CONFLICT (id) DO NOTHING;
