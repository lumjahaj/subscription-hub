-- Transactional outbox for customer-facing email. A billing or dunning
-- change and the notification row it produces commit together in one
-- transaction; a relay job then publishes PENDING rows to SQS, and a
-- listener sends the email. Splitting it this way is what makes "email the
-- customer" survive a rollback and a crash: writing the row here and
-- publishing to a queue can never be one atomic step (the dual-write
-- problem), so the row is the durable half and the queue is only a delivery
-- mechanism replayed from it.
--
-- Content is rendered once, at enqueue time, and stored here rather than
-- recomputed when the relay or listener runs. A row is then a record of
-- exactly what was sent - a later edit to the customer or plan doesn't
-- rewrite history - and delivery needs no business knowledge, only bytes to
-- send.
CREATE TABLE notification (
    id                  uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           varchar(64) NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    -- varchar with a plain CHECK, not the NAMED_ENUM dance: unlike
    -- subscription_status or invoice_status this isn't a Postgres native
    -- enum type, the same choice already made for invoice_line.kind.
    type                varchar(32) NOT NULL
        CHECK (type IN ('INVOICE_ISSUED', 'PAYMENT_FAILED', 'SUBSCRIPTION_CANCELED')),
    -- Stops the same event emailing twice: enqueue does a check-then-insert,
    -- and this is the backstop for the race that check can't close, the
    -- same pattern as uk_invoice_tenant_sub_period.
    dedup_key           varchar(200) NOT NULL,
    recipient           varchar(320) NOT NULL,
    subject             varchar(200) NOT NULL,
    html_body           text        NOT NULL,
    text_body           text        NOT NULL,
    -- Nullable: not every notification is about an invoice, and this is
    -- what the listener reads to find the PDF to attach. ON DELETE SET NULL
    -- rather than CASCADE - an invoice is immutable but not un-deletable in
    -- principle, and a notification record should outlive it.
    invoice_id          uuid        REFERENCES invoice(id) ON DELETE SET NULL,
    status              varchar(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'PUBLISHED', 'SENT')),
    published_at        timestamptz,
    sent_at             timestamptz,
    -- Delivery attempts by the listener, for diagnosis. There is no local
    -- retry loop and no FAILED status here: SQS's redrive policy is the
    -- retry mechanism, and a message that keeps failing ends up on
    -- notifications-dlq, not in this column.
    delivery_attempts   int         NOT NULL DEFAULT 0,
    last_error          text,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_notification_tenant_dedup_key UNIQUE (tenant_id, dedup_key)
);

-- The relay's query: PENDING rows to publish, oldest first, per tenant.
CREATE INDEX idx_notification_tenant_status_created
    ON notification (tenant_id, status, created_at);
