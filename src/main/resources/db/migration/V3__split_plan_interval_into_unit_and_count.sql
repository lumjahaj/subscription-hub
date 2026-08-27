-- Splits plan.interval (a bare "how often" string, always exactly 1
-- MONTH or 1 YEAR) into interval_unit + interval_count, so a plan can bill
-- every N units - e.g. quarterly (MONTH, 3), semi-annual (MONTH, 6),
-- biennial (YEAR, 2) - instead of being locked to a fixed period of 1.
--
-- Mirrors how Stripe's own Price object models recurring billing
-- (interval + interval_count), which matters since Stripe integration is
-- planned later.

CREATE TYPE plan_interval_unit AS ENUM ('MONTH', 'YEAR');

ALTER TABLE plan ADD COLUMN interval_unit  plan_interval_unit;
ALTER TABLE plan ADD COLUMN interval_count int NOT NULL DEFAULT 1;

UPDATE plan SET interval_unit = interval::plan_interval_unit;

ALTER TABLE plan ALTER COLUMN interval_unit SET NOT NULL;
ALTER TABLE plan ADD CONSTRAINT chk_plan_interval_count_positive CHECK (interval_count > 0);

ALTER TABLE plan DROP COLUMN interval;
