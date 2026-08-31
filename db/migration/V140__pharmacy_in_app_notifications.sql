-- Pharmacy in-app notices (tenant-scoped; customer inbox stays customer_id only).
-- Rollback: DROP TABLE IF EXISTS pharmacy_in_app_notifications;

CREATE TABLE pharmacy_in_app_notifications (
    id              UUID PRIMARY KEY,
    pharmacy_id     UUID NOT NULL REFERENCES pharmacies (id),
    type            VARCHAR(20) NOT NULL,
    title           TEXT NOT NULL,
    body            TEXT NOT NULL,
    action_url      TEXT,
    is_read         BOOLEAN NOT NULL DEFAULT FALSE,
    is_deleted      BOOLEAN NOT NULL DEFAULT FALSE,
    read_at         TIMESTAMPTZ,
    expires_at      TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_pharmacy_in_app_notif_type CHECK (type IN (
        'ORDER_UPDATE', 'PROMO', 'REFILL_REMINDER', 'SYSTEM'
    ))
);

CREATE INDEX idx_pharmacy_in_app_pharmacy_created
    ON pharmacy_in_app_notifications (pharmacy_id, created_at DESC)
    WHERE is_deleted = FALSE;

CREATE INDEX idx_pharmacy_in_app_pharmacy_unread
    ON pharmacy_in_app_notifications (pharmacy_id)
    WHERE is_deleted = FALSE AND is_read = FALSE;
