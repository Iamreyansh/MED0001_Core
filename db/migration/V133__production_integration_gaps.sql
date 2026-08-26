-- Production integration gaps: Rx APPROVED status, POS checkout idempotency
-- Rollback:
--   DROP TABLE IF EXISTS pos_checkout_idempotency;
--   ALTER TABLE prescription DROP CONSTRAINT IF EXISTS chk_prescription_status;
--   ALTER TABLE prescription ADD CONSTRAINT chk_prescription_status CHECK (status IN (
--     'UPLOADED', 'PENDING_VERIFICATION', 'VERIFIED', 'REJECTED', 'DISPENSED', 'EXPIRED'
--   ));

ALTER TABLE prescription DROP CONSTRAINT IF EXISTS chk_prescription_status;
ALTER TABLE prescription ADD CONSTRAINT chk_prescription_status CHECK (status IN (
    'UPLOADED', 'PENDING_VERIFICATION', 'VERIFIED', 'APPROVED', 'REJECTED', 'DISPENSED', 'EXPIRED'
));

CREATE TABLE IF NOT EXISTS pos_checkout_idempotency (
    pharmacy_id       UUID         NOT NULL REFERENCES pharmacies (id),
    idempotency_key   VARCHAR(128) NOT NULL,
    cart_id           UUID         NOT NULL REFERENCES pos_cart (id),
    invoice_id        UUID         NOT NULL REFERENCES invoice (id),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (pharmacy_id, idempotency_key)
);
