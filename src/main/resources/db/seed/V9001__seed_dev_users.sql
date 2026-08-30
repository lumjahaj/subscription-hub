-- Development login fixtures. NOT on the Flyway path unless the `dev`
-- profile is active - see spring.flyway.locations in application.yml.
--
-- This is the whole point of the db/migration / db/seed split: the
-- password below is public (it is in .env.example and in this file), so
-- it must be impossible for it to reach an environment that didn't ask
-- for it. A comment saying "dev only" in a migration could not achieve
-- that, because migrations run everywhere.
--
-- Numbered V9001 so seeds can never interleave with schema versions: a
-- future V11 still sorts before every seed, no matter how many are added.
--
-- Idempotent, so re-running against a database that already has them is a
-- no-op rather than a unique-constraint failure.
--
-- Password for all three: "subscriptionhub" (bcrypt, cost 10).
WITH seeded_users AS (
    INSERT INTO app_user (tenant_id, email, password_hash)
    VALUES
        ('acme', 'admin@acme.test',   '$2a$10$weWhaGYAMz9Fzc9M.xikWefRTaxBFywEYnMyYYZYz/LTSnaeP8gci'),
        ('demo', 'admin@demo.test',   '$2a$10$weWhaGYAMz9Fzc9M.xikWefRTaxBFywEYnMyYYZYz/LTSnaeP8gci'),
        ('acme', 'support@acme.test', '$2a$10$weWhaGYAMz9Fzc9M.xikWefRTaxBFywEYnMyYYZYz/LTSnaeP8gci')
    ON CONFLICT ON CONSTRAINT uk_app_user_tenant_email DO NOTHING
    RETURNING id, email
)
INSERT INTO app_user_role (user_id, role)
SELECT id, CASE WHEN email LIKE 'support@%' THEN 'SUPPORT' ELSE 'ADMIN' END
FROM seeded_users
ON CONFLICT DO NOTHING;
