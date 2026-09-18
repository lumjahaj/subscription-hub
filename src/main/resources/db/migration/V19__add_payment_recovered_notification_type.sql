-- A fourth notification type: the customer who was told a payment failed is
-- now told it went through.
--
-- notification.type is varchar(32) with a CHECK rather than a Postgres enum
-- (V14), so widening the vocabulary is a constraint swap and not the
-- NAMED_ENUM dance invoice_status needs. The constraint is recreated under
-- its existing auto-generated name, so nothing else has to know it changed.
ALTER TABLE notification DROP CONSTRAINT notification_type_check;

ALTER TABLE notification ADD CONSTRAINT notification_type_check
    CHECK (type IN ('INVOICE_ISSUED', 'PAYMENT_FAILED', 'PAYMENT_RECOVERED', 'SUBSCRIPTION_CANCELED'));
