-- Activates the invoice / invoice_line tables, which have existed unused
-- since V1. Six defects, in rough order of severity - the first three
-- would stop the application booting under ddl-auto: validate, the rest
-- make invoicing either unsafe or unanswerable.
--
-- 1. invoice_line has neither created_at nor updated_at. Every entity here
--    extends TenantScoped, which maps both NOT NULL and whose listener
--    always sets them. Exactly the defect V5 fixed for usage_counter.
--
-- 2. invoice.currency is char(3). Postgres reports that as bpchar
--    (Types#CHAR); Hibernate expects varchar (Types#VARCHAR) for a
--    @Column(length = 3) String and rejects the mismatch outright. A
--    startup failure, not a cosmetic difference. plan.currency is already
--    varchar(3), and an invoice copies its plan's currency verbatim, so
--    the two must agree.
--
-- 3. subscription_id and customer_id are NOT NULL but ON DELETE SET NULL -
--    the delete would violate the very constraint the rule works around,
--    so it could only ever error. An invoice records a charge that really
--    happened; RESTRICT says so outright rather than pretending an invoice
--    can outlive the subscription it bills.
--
-- 4. V1 gave invoice no notion of the period it bills. issued_at records
--    when the invoice was produced, never what it covers, so "has this
--    subscription already been billed for this period?" was unanswerable
--    and nothing prevented billing a period twice. period_start /
--    period_end plus a unique key make double-billing a database error
--    instead of a code convention - the same move usage_counter's unique
--    key made, and deliberately the same column name, because an
--    invoice's usage lines are read from usage_counter on exactly it.
--
-- 5. The (tenant_id, number) constraint still carries Postgres's
--    auto-generated name. Renamed to the uk_* convention V4 and V5
--    established, so ProblemDetailsAdvice has a stable name to map to a
--    409 rather than letting the violation fall through to a 500.
--
-- 6. invoice_line(invoice_id) is an unindexed foreign key, and every read
--    of an invoice loads its lines by it. The unique key added in (4)
--    already indexes (tenant_id, subscription_id, ...), so invoice itself
--    needs no additional index.
--
-- Both tables are empty - nothing has ever written to them - so the NOT
-- NULL columns in (4) are added directly, with no backfill and no
-- add-default-then-drop dance.

ALTER TABLE invoice_line ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE invoice_line ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();

ALTER TABLE invoice ALTER COLUMN currency TYPE varchar(3);

ALTER TABLE invoice DROP CONSTRAINT invoice_subscription_id_fkey;
ALTER TABLE invoice ADD CONSTRAINT fk_invoice_subscription
    FOREIGN KEY (subscription_id) REFERENCES subscription(id) ON DELETE RESTRICT;

ALTER TABLE invoice DROP CONSTRAINT invoice_customer_id_fkey;
ALTER TABLE invoice ADD CONSTRAINT fk_invoice_customer
    FOREIGN KEY (customer_id) REFERENCES customer(id) ON DELETE RESTRICT;

ALTER TABLE invoice ADD COLUMN period_start timestamptz NOT NULL;
ALTER TABLE invoice ADD COLUMN period_end   timestamptz NOT NULL;
ALTER TABLE invoice ADD CONSTRAINT uk_invoice_tenant_sub_period
    UNIQUE (tenant_id, subscription_id, period_start);

ALTER TABLE invoice RENAME CONSTRAINT invoice_tenant_id_number_key TO uk_invoice_tenant_number;

CREATE INDEX idx_invoice_line_invoice ON invoice_line(invoice_id);

-- Per-tenant, human-readable, gap-free invoice numbers.
--
-- SELECT max(number) + 1 followed by an INSERT races the same way the
-- usage counter's read-modify-write did (see V5 and
-- UsageCounterJpaRepository) - two concurrent invoices for one tenant
-- both read the same max and both try to write the same number. The
-- allocation is therefore a single INSERT ... ON CONFLICT DO UPDATE ...
-- RETURNING against this table, so concurrent allocations queue on the
-- tenant's row rather than collide.
--
-- Not a Postgres sequence: a sequence is global, and numbering restarts
-- per tenant. A sequence also doesn't roll back, so an aborted
-- transaction burns a number - and gap-free invoice numbering is a legal
-- requirement in several jurisdictions. A counter row rolls back with the
-- invoice that allocated it. The cost is that invoice creation serializes
-- per tenant for the length of the transaction, the right trade at this
-- scale.
CREATE TABLE invoice_number_sequence (
    tenant_id   varchar(64) PRIMARY KEY REFERENCES tenant(id) ON DELETE CASCADE,
    last_value  bigint      NOT NULL DEFAULT 0,
    updated_at  timestamptz NOT NULL DEFAULT now()
);
