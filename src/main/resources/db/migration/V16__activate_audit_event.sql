-- Activates audit_event, which has existed unused since V1. It waited for
-- authentication: an audit log exists to record *who* did something, and
-- until tokens existed there was no one to record.
--
-- Nothing has ever written to this table, so every column change below is
-- safe on any existing database - including the NOT NULL additions, which
-- would otherwise need a backfill.

-- "actor" held a free-text "username, client id, system, etc.". Split into
-- a kind and an identifier, because the two things a reader asks - was this
-- a person or the system, and which person - should not require parsing a
-- string. The identifier is the token's subject (a user or platform-user
-- UUID), never an email: emails change and are personal data, while an
-- audit row is kept for good.
ALTER TABLE audit_event RENAME COLUMN actor TO actor_id;
ALTER TABLE audit_event ALTER COLUMN actor_id TYPE varchar(64);

-- varchar + CHECK rather than a Postgres enum, the same choice as
-- invoice_line.kind and notification.type: a native enum needs the
-- NAMED_ENUM mapping and an ALTER TYPE for every new value.
ALTER TABLE audit_event ADD COLUMN actor_type varchar(16) NOT NULL;
ALTER TABLE audit_event ADD CONSTRAINT chk_audit_event_actor_type
    CHECK (actor_type IN ('USER', 'PLATFORM_ADMIN', 'SYSTEM'));

-- A SYSTEM event (a scheduled job, a provider webhook) has no principal;
-- every other kind must name one.
ALTER TABLE audit_event ADD CONSTRAINT chk_audit_event_actor_id_present
    CHECK (actor_type = 'SYSTEM' OR actor_id IS NOT NULL);

-- The MDC request id, so an event can be traced to the log lines of the
-- request that caused it. Null for work no HTTP request started.
ALTER TABLE audit_event ADD COLUMN request_id varchar(64);

-- Every event is about some record; an event about nothing cannot be
-- filtered or followed.
ALTER TABLE audit_event ALTER COLUMN entity_type SET NOT NULL;
ALTER TABLE audit_event ALTER COLUMN entity_id TYPE varchar(64);
ALTER TABLE audit_event ALTER COLUMN entity_id SET NOT NULL;

-- "What happened to this subscription?" - the other read besides the
-- tenant-wide timeline idx_audit_tenant_created already serves.
CREATE INDEX idx_audit_event_tenant_entity
    ON audit_event (tenant_id, entity_type, entity_id, created_at DESC);
