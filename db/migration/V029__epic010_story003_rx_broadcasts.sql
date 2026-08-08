-- EPIC-010 / STORY-003: prescription quote broadcast to nearby pharmacies
-- Rollback: DROP INDEX IF EXISTS idx_rx_bp_pharmacy_pending;
--           DROP INDEX IF EXISTS idx_rx_bp_broadcast;
--           DROP INDEX IF EXISTS idx_rx_broadcasts_expire;
--           DROP INDEX IF EXISTS idx_rx_broadcasts_customer;
--           DROP TABLE IF EXISTS rx_broadcast_pharmacies;
--           DROP TABLE IF EXISTS rx_broadcasts;
-- Notes: money as BIGINT paise; prescription_id has no FK until EPIC-008;
--        medicines_requested is redacted OCR/e-Rx list (no Rx file).

CREATE TABLE rx_broadcasts (
    id                   UUID PRIMARY KEY,
    customer_id          UUID NOT NULL REFERENCES customers (id),
    prescription_id      UUID NOT NULL,
    delivery_address_id  UUID NOT NULL REFERENCES customer_addresses (id),
    patient_name         VARCHAR(200) NOT NULL,
    notes                VARCHAR(300) NULL,
    medicines_requested  JSONB NOT NULL DEFAULT '[]'::jsonb,
    status               VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    pharmacies_notified  INT NOT NULL,
    broadcast_at         TIMESTAMPTZ NOT NULL,
    expires_at           TIMESTAMPTZ NOT NULL,
    selected_pharmacy_id UUID NULL REFERENCES pharmacies (id),
    resulting_cart_id    UUID NULL REFERENCES carts (id),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_rx_broadcasts_status CHECK (status IN ('ACTIVE', 'SELECTED', 'EXPIRED'))
);

CREATE INDEX idx_rx_broadcasts_customer
    ON rx_broadcasts (customer_id, broadcast_at DESC);

CREATE INDEX idx_rx_broadcasts_expire
    ON rx_broadcasts (expires_at)
    WHERE status = 'ACTIVE';

CREATE TABLE rx_broadcast_pharmacies (
    id                   UUID PRIMARY KEY,
    broadcast_id         UUID NOT NULL REFERENCES rx_broadcasts (id),
    pharmacy_id          UUID NOT NULL REFERENCES pharmacies (id),
    distance_km          NUMERIC(8, 3) NOT NULL,
    status               VARCHAR(20) NOT NULL,
    medicines_available  JSONB NULL,
    delivery_eta_minutes INT NULL,
    total_payable_paise  BIGINT NULL,
    received_at          TIMESTAMPTZ NOT NULL,
    response_deadline    TIMESTAMPTZ NOT NULL,
    quoted_at            TIMESTAMPTZ NULL,
    quote_expires_at     TIMESTAMPTZ NULL,
    tags                 TEXT[] NULL,
    CONSTRAINT uq_rx_bp_broadcast_pharmacy UNIQUE (broadcast_id, pharmacy_id),
    CONSTRAINT chk_rx_bp_status CHECK (
        status IN ('NOTIFIED', 'REVIEWING', 'QUOTED', 'OUT_OF_STOCK', 'EXPIRED')
    )
);

CREATE INDEX idx_rx_bp_broadcast
    ON rx_broadcast_pharmacies (broadcast_id);

CREATE INDEX idx_rx_bp_pharmacy_pending
    ON rx_broadcast_pharmacies (pharmacy_id, response_deadline)
    WHERE status IN ('NOTIFIED', 'REVIEWING');
