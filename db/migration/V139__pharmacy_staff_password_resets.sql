-- Pharmacy staff password reset tokens (hash only; plaintext returned once on owner-issued reset).
-- Rollback: DROP TABLE IF EXISTS pharmacy_staff_password_resets;

CREATE TABLE pharmacy_staff_password_resets (
    id           UUID PRIMARY KEY,
    staff_id     UUID NOT NULL REFERENCES pharmacy_staff (id),
    token_hash   VARCHAR(64) NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL,
    used_at      TIMESTAMPTZ NULL,
    created_at   TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX idx_pharmacy_staff_resets_active_hash
    ON pharmacy_staff_password_resets (token_hash)
    WHERE used_at IS NULL;

CREATE INDEX idx_pharmacy_staff_resets_staff
    ON pharmacy_staff_password_resets (staff_id);
