-- Payments: one row per attempt to collect an invoice through a payment
-- provider (a local fake by default, Stripe when configured).
--
-- A payment is created PENDING before the provider is called and is only
-- settled to SUCCEEDED or FAILED by a provider event (a webhook, for a real
-- provider). The API call's own response is never treated as the outcome -
-- the same model Stripe's PaymentIntents use, so a real provider can be
-- plugged in without changing where money is settled.

CREATE TYPE payment_status AS ENUM ('PENDING', 'SUCCEEDED', 'FAILED');

CREATE TABLE payment (
    id                  uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           varchar(64) NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    -- RESTRICT, not CASCADE: a payment records money that moved (or was
    -- attempted), and should never disappear because an invoice row did.
    -- Same reasoning V6 applied to invoice -> subscription.
    invoice_id          uuid        NOT NULL REFERENCES invoice(id) ON DELETE RESTRICT,
    amount_cents        bigint      NOT NULL,
    currency            varchar(3)  NOT NULL,
    status              payment_status NOT NULL,
    -- Which adapter handled it ("fake", later "stripe"). Switching the
    -- configured provider must not let one provider's events settle the
    -- other's payments, and a reference is only meaningful to its issuer.
    provider            varchar(32) NOT NULL,
    payment_method      varchar(64) NOT NULL,
    -- NULL until the provider has acknowledged the payment.
    provider_reference  varchar(128),
    failure_code        varchar(64),
    -- Client-supplied Idempotency-Key header, unique per tenant.
    idempotency_key     varchar(128) NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_payment_tenant_idempotency_key UNIQUE (tenant_id, idempotency_key)
);

-- The structural guard against charging an invoice twice: at most one
-- payment per invoice may be in flight or have succeeded. FAILED rows are
-- excluded, so a declined card can be retried. Code checks the invoice is
-- OPEN first, but two concurrent requests can both pass that check; this
-- index is what makes the loser fail instead of reaching the provider.
-- A partial unique index rather than a constraint, because a UNIQUE
-- constraint cannot carry a WHERE clause.
CREATE UNIQUE INDEX ux_payment_invoice_in_flight_or_succeeded
    ON payment (tenant_id, invoice_id)
    WHERE status IN ('PENDING', 'SUCCEEDED');

CREATE INDEX idx_payment_invoice ON payment (invoice_id);

-- When the invoice was settled. NULL for every invoice that isn't PAID.
ALTER TABLE invoice ADD COLUMN paid_at timestamptz;
