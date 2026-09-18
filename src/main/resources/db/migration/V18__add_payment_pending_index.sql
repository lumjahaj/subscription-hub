-- Reconciliation sweeps payments still PENDING past a cutoff, per tenant.
--
-- Partial, on the same reasoning as the notification outbox indexes: a
-- payment is PENDING only while it is in flight, so this index holds the
-- few rows still waiting rather than growing with every payment ever made.
CREATE INDEX idx_payment_pending_created
    ON payment (tenant_id, created_at)
    WHERE status = 'PENDING';
