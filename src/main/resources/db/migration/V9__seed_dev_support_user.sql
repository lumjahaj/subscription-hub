-- A second seeded user, holding SUPPORT rather than ADMIN.
--
-- Without one, every test and every manual request authenticates as an
-- ADMIN, so role checks would be asserted only in the direction that
-- passes: nothing would prove that a lesser role is actually refused, or
-- that a refusal is a 403 rather than a 401. The RBAC tests need an
-- account that is genuinely authenticated but genuinely not allowed.
--
-- Development fixture, same as the admins V8 seeds and the acme/demo
-- tenants V1 seeds - the password is public and this must not reach a
-- real deployment. All of these move out of db/migration into a
-- profile-gated db/seed location in the next commit, which is what
-- actually enforces that rather than just asserting it in a comment.
--
-- Same bcrypt hash of "subscriptionhub" as the seeded admins.
WITH seeded_support AS (
    INSERT INTO app_user (tenant_id, email, password_hash)
    VALUES ('acme', 'support@acme.test', '$2a$10$weWhaGYAMz9Fzc9M.xikWefRTaxBFywEYnMyYYZYz/LTSnaeP8gci')
    ON CONFLICT ON CONSTRAINT uk_app_user_tenant_email DO NOTHING
    RETURNING id
)
INSERT INTO app_user_role (user_id, role)
SELECT id, 'SUPPORT' FROM seeded_support
ON CONFLICT DO NOTHING;
