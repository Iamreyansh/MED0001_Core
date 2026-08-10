-- EPIC-009 / STORY-002: patient teleconsult requests (NOW + scheduled)
-- Rollback:
--   DROP INDEX IF EXISTS idx_consults_auto_cancel;
--   DROP INDEX IF EXISTS idx_consults_cart_active;
--   DROP INDEX IF EXISTS idx_consults_customer_active;
--   DROP INDEX IF EXISTS idx_consults_customer_created;
--   DROP TABLE IF EXISTS consults;
-- Notes: Free for patients. UUID v4 ids (no consult_ prefixes). patient_phone never in list API.
--        e-Rx cart link (AC-005) deferred to STORY-004; cart_id validated via CartPort bridge.

CREATE TABLE consults (
    id                      UUID PRIMARY KEY,
    customer_id             UUID NOT NULL,
    doctor_id               UUID NULL REFERENCES teleconsult_doctors (id),
    patient_name            VARCHAR(200) NOT NULL,
    patient_phone           VARCHAR(32) NOT NULL,
    slot_type               VARCHAR(16) NOT NULL,
    scheduled_at            TIMESTAMPTZ NULL,
    symptoms                JSONB NULL,
    medicines_needing_rx    JSONB NULL,
    cart_id                 UUID NULL,
    is_cart_mode            BOOLEAN NOT NULL DEFAULT FALSE,
    reason                  VARCHAR(32) NOT NULL,
    status                  VARCHAR(32) NOT NULL DEFAULT 'REQUESTED',
    call_started_at         TIMESTAMPTZ NULL,
    call_ended_at           TIMESTAMPTZ NULL,
    e_prescription_id       UUID NULL,
    is_advice_only          BOOLEAN NOT NULL DEFAULT FALSE,
    rating                  SMALLINT NULL,
    feedback_text           VARCHAR(500) NULL,
    auto_cancelled_reason   VARCHAR(200) NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at              TIMESTAMPTZ NULL,
    CONSTRAINT chk_consults_slot_type CHECK (slot_type IN ('NOW', 'SCHEDULED')),
    CONSTRAINT chk_consults_reason CHECK (reason IN ('GENERAL', 'RX_NEEDED')),
    CONSTRAINT chk_consults_status CHECK (
        status IN (
            'REQUESTED',
            'DOCTOR_REVIEWING',
            'CALLING',
            'IN_CALL',
            'COMPLETED',
            'CANCELLED'
        )
    ),
    CONSTRAINT chk_consults_rating CHECK (rating IS NULL OR (rating >= 1 AND rating <= 5)),
    CONSTRAINT chk_consults_scheduled_slot CHECK (
        (slot_type = 'NOW' AND scheduled_at IS NULL)
        OR (slot_type = 'SCHEDULED' AND scheduled_at IS NOT NULL)
    ),
    CONSTRAINT chk_consults_cart_mode CHECK (
        (is_cart_mode = FALSE AND cart_id IS NULL)
        OR (is_cart_mode = TRUE AND cart_id IS NOT NULL)
    )
);

CREATE INDEX idx_consults_customer_created
    ON consults (customer_id, created_at DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_consults_customer_active
    ON consults (customer_id)
    WHERE deleted_at IS NULL
      AND status NOT IN ('COMPLETED', 'CANCELLED');

CREATE UNIQUE INDEX idx_consults_cart_active
    ON consults (cart_id)
    WHERE deleted_at IS NULL
      AND cart_id IS NOT NULL
      AND is_cart_mode = TRUE
      AND status NOT IN ('COMPLETED', 'CANCELLED');

CREATE INDEX idx_consults_auto_cancel
    ON consults (scheduled_at)
    WHERE deleted_at IS NULL
      AND slot_type = 'SCHEDULED'
      AND status IN ('REQUESTED', 'DOCTOR_REVIEWING');

CREATE INDEX idx_consults_now_queue
    ON consults (created_at)
    WHERE deleted_at IS NULL
      AND slot_type = 'NOW'
      AND status = 'REQUESTED'
      AND doctor_id IS NULL;
