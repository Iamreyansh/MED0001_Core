-- EPIC-021 / STORY-001: admin staff management (invite/reset tokens)
-- Rollback:
--   ALTER TABLE admin_staff DROP COLUMN IF EXISTS reset_token_expires_at;
--   ALTER TABLE admin_staff DROP COLUMN IF EXISTS reset_token_hash;
--   ALTER TABLE admin_staff DROP COLUMN IF EXISTS invite_token_hash;
--   ALTER TABLE admin_staff DROP COLUMN IF EXISTS invite_expires_at;
--   ALTER TABLE admin_staff ALTER COLUMN password_hash SET NOT NULL;
-- Notes: password_hash nullable while status=INVITED; tokens stored as SHA-256 hex only.

ALTER TABLE admin_staff
    ALTER COLUMN password_hash DROP NOT NULL;

ALTER TABLE admin_staff
    ADD COLUMN IF NOT EXISTS invite_expires_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS invite_token_hash VARCHAR(64),
    ADD COLUMN IF NOT EXISTS reset_token_hash VARCHAR(64),
    ADD COLUMN IF NOT EXISTS reset_token_expires_at TIMESTAMPTZ;
