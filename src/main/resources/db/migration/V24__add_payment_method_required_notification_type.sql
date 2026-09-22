-- A fifth notification type: the customer whose invoice cannot be collected
-- because no payment method is stored is asked to add one. Until now that
-- invoice was skipped silently and nobody was ever told.
--
-- The same constraint swap as V19, for the same reason: notification.type is
-- varchar(32) with a CHECK (V14) rather than a Postgres enum, so widening the
-- vocabulary needs neither a new type nor the NAMED_ENUM mapping combo
-- invoice_status requires. Recreated under its existing auto-generated name,
-- so nothing else has to know the constraint changed.
ALTER TABLE notification DROP CONSTRAINT notification_type_check;

ALTER TABLE notification ADD CONSTRAINT notification_type_check
    CHECK (type IN ('INVOICE_ISSUED', 'PAYMENT_FAILED', 'PAYMENT_RECOVERED',
                    'PAYMENT_METHOD_REQUIRED', 'SUBSCRIPTION_CANCELED'));
