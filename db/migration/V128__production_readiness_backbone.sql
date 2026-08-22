-- Production readiness: outbox lease, scheduler lease, payment idempotency, inventory hold
-- Rollback:
--   DROP TABLE IF EXISTS inventory_reservation;
--   DROP TABLE IF EXISTS scheduler_lease;
--   ALTER TABLE outbox_message DROP COLUMN IF EXISTS locked_at;
--   ALTER TABLE outbox_message DROP COLUMN IF EXISTS locked_by;
--   ALTER TABLE outbox_message DROP COLUMN IF EXISTS attempts;
--   ALTER TABLE outbox_message DROP COLUMN IF EXISTS last_error;
--   ALTER TABLE outbox_message DROP COLUMN IF EXISTS published_at;
--   DROP INDEX IF EXISTS uq_payment_idempotency_key;
--   DROP INDEX IF EXISTS uq_kyc_documents_active_type;
--   CREATE UNIQUE INDEX uq_kyc_documents_active_type ON kyc_documents (pharmacy_id, document_type)
--     WHERE deleted_at IS NULL AND status IN ('UPLOADED', 'UNDER_REVIEW', 'VERIFIED');
-- Notes: money BIGINT paise; UUID ids; TIMESTAMPTZ. SCAN_CLEAN is post-malware-scan KYC status (D7).

ALTER TABLE outbox_message
    ADD COLUMN IF NOT EXISTS locked_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS locked_by VARCHAR(128),
    ADD COLUMN IF NOT EXISTS attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_error TEXT,
    ADD COLUMN IF NOT EXISTS published_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_outbox_claimable
    ON outbox_message (published, locked_at, created_at)
    WHERE published = FALSE;

CREATE TABLE IF NOT EXISTS scheduler_lease (
    job_name      VARCHAR(160) PRIMARY KEY,
    locked_by     VARCHAR(128) NOT NULL,
    locked_until  TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_idempotency_key
    ON payment (idempotency_key)
    WHERE idempotency_key IS NOT NULL AND idempotency_key <> '';

CREATE TABLE IF NOT EXISTS inventory_reservation (
    id              UUID PRIMARY KEY,
    order_id        UUID NOT NULL,
    pharmacy_id     UUID NOT NULL,
    medicine_id     UUID NOT NULL,
    quantity        INTEGER NOT NULL CHECK (quantity > 0),
    status          VARCHAR(16) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_inventory_reservation_status
        CHECK (status IN ('RESERVED', 'DEDUCTED', 'RELEASED'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_inventory_reservation_order_medicine
    ON inventory_reservation (order_id, medicine_id);

CREATE INDEX IF NOT EXISTS idx_inventory_reservation_order
    ON inventory_reservation (order_id, status);

ALTER TABLE inventory_stock_movement
    DROP CONSTRAINT IF EXISTS chk_inventory_stock_movement_type;

ALTER TABLE inventory_stock_movement
    ADD CONSTRAINT chk_inventory_stock_movement_type CHECK (
        movement_type IN ('RECEIPT', 'ADJUSTMENT', 'WRITE_OFF', 'SALE', 'RESERVE', 'RELEASE', 'DEDUCT')
    );

DROP INDEX IF EXISTS uq_kyc_documents_active_type;
CREATE UNIQUE INDEX uq_kyc_documents_active_type
    ON kyc_documents (pharmacy_id, document_type)
    WHERE deleted_at IS NULL AND status IN ('UPLOADED', 'SCAN_CLEAN', 'UNDER_REVIEW', 'VERIFIED');
