-- Development platform administrator. Same rules as V9001: only on the
-- Flyway path under the `dev` profile, because the password is public.
--
-- A real deployment gets its first platform admin from
-- PLATFORM_ADMIN_EMAIL / PLATFORM_ADMIN_PASSWORD instead (see
-- PlatformAdminBootstrap), which creates one only while this table is empty.
--
-- Password: "subscriptionhub" (bcrypt, cost 10) - the same hash as V9001.
INSERT INTO platform_user (email, password_hash)
VALUES ('platform@subscriptionhub.test', '$2a$10$weWhaGYAMz9Fzc9M.xikWefRTaxBFywEYnMyYYZYz/LTSnaeP8gci')
ON CONFLICT ON CONSTRAINT uk_platform_user_email DO NOTHING;
