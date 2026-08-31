CREATE TABLE pharmacy_staff_invites (
    id           UUID PRIMARY KEY,
    staff_id     UUID NOT NULL REFERENCES pharmacy_staff (id),
    pharmacy_id  UUID NOT NULL REFERENCES pharmacies (id),
    token_hash   VARCHAR(64) NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL,
    used_at      TIMESTAMPTZ NULL,
    created_at   TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX idx_pharmacy_staff_invites_active_hash
    ON pharmacy_staff_invites (token_hash)
    WHERE used_at IS NULL;

CREATE INDEX idx_pharmacy_staff_invites_staff
    ON pharmacy_staff_invites (staff_id, pharmacy_id);
