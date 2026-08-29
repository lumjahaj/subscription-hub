-- The entity classes already declare @UniqueConstraint(name = "uk_..."),
-- but Hibernate only uses that name for DDL generation, which is off
-- (ddl-auto: validate). V1 created these constraints inline without a
-- name, so Postgres assigned its default naming (table_col1_col2_key).
-- Renaming them to match the entity annotations closes that silent
-- mismatch and gives ProblemDetailsAdvice a stable, intentional name to
-- match on when mapping a unique-constraint violation to 409.
ALTER TABLE product RENAME CONSTRAINT product_tenant_id_code_key TO uk_product_tenant_code;
ALTER TABLE customer RENAME CONSTRAINT customer_tenant_id_email_key TO uk_customer_tenant_email;
ALTER TABLE plan RENAME CONSTRAINT plan_tenant_id_code_key TO uk_plan_tenant_code;
ALTER TABLE plan_entitlement RENAME CONSTRAINT plan_entitlement_tenant_id_plan_id_key_key TO uk_plan_ent_tenant_plan_key;
