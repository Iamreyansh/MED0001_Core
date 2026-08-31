-- POS insurance/TPA claim stub (no live TPA network).
-- Rollback: DROP TABLE IF EXISTS pos_insurance_claim;

CREATE TABLE pos_insurance_claim (
    id              UUID PRIMARY KEY,
    pharmacy_id     UUID NOT NULL REFERENCES pharmacies (id),
    invoice_id      UUID NOT NULL REFERENCES invoice (id),
    tpa_name        VARCHAR(120),
    policy_number   VARCHAR(80),
    status          VARCHAR(20) NOT NULL DEFAULT 'SUBMITTED',
    notes           VARCHAR(500),
    created_by      UUID NOT NULL REFERENCES pharmacy_staff (id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_pos_insurance_claim_status CHECK (status IN (
        'SUBMITTED', 'PENDING', 'APPROVED', 'REJECTED'
    )),
    CONSTRAINT uq_pos_insurance_claim_invoice UNIQUE (invoice_id)
);

CREATE INDEX idx_pos_insurance_claim_pharmacy
    ON pos_insurance_claim (pharmacy_id, created_at DESC);
