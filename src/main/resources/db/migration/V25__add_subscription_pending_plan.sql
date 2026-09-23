-- A plan change scheduled for the subscription's next billing period.
--
-- Why a column on subscription rather than a table: at most one change can be
-- pending at a time (a second request replaces the first), it carries no
-- history of its own - the audit log is the history - and it has to be applied
-- in the *same* conditional UPDATE as the renewal, which a separate table
-- would make a second statement that could succeed or fail independently.
--
-- Why nothing takes effect until renewal: InvoiceCalculator reads the plan's
-- price when the invoice is generated, so swapping plan_id when the request
-- arrives would bill the whole closed period at the new price. BillingCycleJob
-- already invoices before it renews, so deferring the swap to the renewal makes
-- that mis-billing structurally impossible.
--
-- ON DELETE RESTRICT matches plan_id (V1): a plan a subscription is moving to
-- is no more deletable than the one it is on.
ALTER TABLE subscription
    ADD COLUMN pending_plan_id uuid REFERENCES plan(id) ON DELETE RESTRICT;

-- No index: the column is only ever read through a subscription already
-- located by primary key, never searched on.
