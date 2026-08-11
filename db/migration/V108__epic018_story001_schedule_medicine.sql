-- EPIC-018 / STORY-001: schedule_medicine
-- Rollback:
--   DROP INDEX IF EXISTS idx_schedule_medicine_active;
--   DROP INDEX IF EXISTS idx_schedule_medicine_customer_member;
--   DROP TABLE IF EXISTS schedule_medicine;
-- Notes: dose_slots JSONB; soft archive via is_active + ended_on_date (no deleted_at);
--   master_medicine_id stored without FK (optional catalog link; validated later).

CREATE TABLE schedule_medicine (
    id                      UUID PRIMARY KEY,
    customer_id             UUID NOT NULL REFERENCES customers (id),
    member_id               UUID NOT NULL REFERENCES care_circle_member (id),
    master_medicine_id      UUID NULL,
    medicine_name           VARCHAR(200) NOT NULL,
    strength                VARCHAR(50) NULL,
    dose                    VARCHAR(100) NOT NULL,
    form                    VARCHAR(20) NOT NULL,
    dose_slots              JSONB NOT NULL,
    food_instruction        VARCHAR(10) NOT NULL,
    duration_type           VARCHAR(10) NOT NULL,
    duration_days           INTEGER NULL,
    started_on_date         DATE NOT NULL,
    ended_on_date           DATE NULL,
    condition_name          VARCHAR(200) NULL,
    prescribed_by           VARCHAR(200) NULL,
    units_in_hand           INTEGER NOT NULL DEFAULT 0,
    refill_remind_at_units  INTEGER NOT NULL DEFAULT 0,
    notes                   TEXT NULL,
    is_active               BOOLEAN NOT NULL DEFAULT TRUE,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_schedule_medicine_form CHECK (form IN (
        'TABLET', 'SYRUP', 'CAPSULE', 'DROPS', 'INJECTION', 'OTHER'
    )),
    CONSTRAINT chk_schedule_medicine_food CHECK (food_instruction IN (
        'BEFORE', 'AFTER', 'ANY'
    )),
    CONSTRAINT chk_schedule_medicine_duration_type CHECK (duration_type IN (
        'ONGOING', 'DAYS'
    )),
    CONSTRAINT chk_schedule_medicine_duration_days CHECK (
        duration_days IS NULL OR duration_days > 0
    ),
    CONSTRAINT chk_schedule_medicine_units CHECK (units_in_hand >= 0),
    CONSTRAINT chk_schedule_medicine_refill CHECK (refill_remind_at_units >= 0),
    CONSTRAINT chk_schedule_medicine_dose_slots CHECK (jsonb_typeof(dose_slots) = 'array')
);

CREATE INDEX idx_schedule_medicine_customer_member
    ON schedule_medicine (customer_id, member_id);

CREATE INDEX idx_schedule_medicine_active
    ON schedule_medicine (is_active);
