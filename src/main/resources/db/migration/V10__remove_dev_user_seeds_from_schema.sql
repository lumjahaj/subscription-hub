-- Takes the seeded logins back out of the schema.
--
-- V8 and V9 created users with a password that is public - the bcrypt
-- hash is in this repository's history and the plaintext is in
-- .env.example. Flyway has no notion of environment: `locations:
-- classpath:db/migration` is unconditional, so those migrations run in
-- *every* environment, including a real one. Calling them
-- "development only" in a comment documented the risk without doing
-- anything about it.
--
-- They are recreated by db/seed/V9001__seed_dev_users.sql, which is only
-- on the Flyway path when the `dev` profile is active. Ordering works out:
-- Flyway sorts every location together, so V10 (here) always runs before
-- V9001, whether or not the seed location is present. Without the profile
-- the users are created by V8/V9 and removed again here, netting zero.
--
-- Deletes by exact identity rather than truncating app_user: this must
-- remove the three known fixtures and nothing an operator has since
-- created. app_user_role rows follow via ON DELETE CASCADE.
DELETE FROM app_user
WHERE (tenant_id, email) IN (
    ('acme', 'admin@acme.test'),
    ('demo', 'admin@demo.test'),
    ('acme', 'support@acme.test')
);

-- The seeded acme/demo TENANTS deliberately stay in V1, for two reasons.
--
-- They carry no credentials, so they are untidy in production rather than
-- dangerous - a tenant row is (id, name, active).
--
-- More importantly, deleting them here would be destructive and would
-- make things worse, not better. Every one of the twelve tables
-- referencing tenant does so ON DELETE CASCADE, so dropping the acme row
-- would silently take every customer, product, subscription, invoice and
-- usage counter with it. And there is currently no way to create a tenant
-- at all - TenantRepository has no save, and no provisioning endpoint
-- exists - so a deployment stripped of its seeded tenants would have no
-- tenants and no means of getting one. Revisit when the platform-admin
-- provisioning API lands; tracked in CLAUDE.md §9.
