-- Retry bookkeeping for an invoice that has been issued but not paid.
--
-- A separate table rather than columns on invoice: an invoice is immutable
-- once issued (see V6 and InvoiceService), and how many times we have tried
-- to collect it is operational state, not part of the financial document.
-- It also means the row can simply be deleted when the invoice is settled,
-- leaving the invoice untouched.
--
-- next_attempt_at is what DunningJob queries, so the schedule lives in the
-- database rather than being recomputed from payment history on every run -
-- and a support engineer can see when the next attempt is due.
CREATE TABLE dunning_state (
    id                  uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           varchar(64) NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    -- CASCADE, unlike payment -> invoice: this row records no money, only
    -- our own retry schedule, so it is meaningless without its invoice.
    invoice_id          uuid        NOT NULL REFERENCES invoice(id) ON DELETE CASCADE,
    -- Attempts started, not attempts failed: incremented before the provider
    -- is called, so a crash mid-attempt costs one retry rather than causing
    -- an immediate retry storm.
    attempt_count       int         NOT NULL DEFAULT 0,
    next_attempt_at     timestamptz NOT NULL,
    last_failure_code   varchar(64),
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_dunning_state_tenant_invoice UNIQUE (tenant_id, invoice_id)
);

-- The job's query: rows due for another attempt, per tenant.
CREATE INDEX idx_dunning_state_next_attempt ON dunning_state (tenant_id, next_attempt_at);
