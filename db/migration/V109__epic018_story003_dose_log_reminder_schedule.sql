-- EPIC-018 / STORY-003: dose_log + reminder_schedule
-- Rollback:
--   DROP INDEX IF EXISTS idx_reminder_schedule_dose_log;
--   DROP INDEX IF EXISTS idx_reminder_schedule_customer;
--   DROP INDEX IF EXISTS idx_reminder_schedule_status;
--   DROP INDEX IF EXISTS idx_reminder_schedule_scheduled_at;
--   DROP TABLE IF EXISTS reminder_schedule;
--   DROP INDEX IF EXISTS idx_dose_log_member_date;
--   DROP INDEX IF EXISTS idx_dose_log_customer_date;
--   DROP TABLE IF EXISTS dose_log;
-- Notes: UNIQUE (medicine_id, dose_date, slot); one ReminderSchedule per DoseLog;
--   soft-cancel reminders via status=CANCELLED (no deleted_at).

CREATE TABLE dose_log (
    id              UUID PRIMARY KEY,
    medicine_id     UUID NOT NULL REFERENCES schedule_medicine (id),
    customer_id     UUID NOT NULL REFERENCES customers (id),
    member_id       UUID NOT NULL REFERENCES care_circle_member (id),
    dose_date       DATE NOT NULL,
    slot            VARCHAR(20) NOT NULL,
    reminder_time   TIME NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'UPCOMING',
    taken_at        TIMESTAMPTZ NULL,
    is_locked       BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_dose_log_slot CHECK (slot IN (
        'MORNING', 'AFTERNOON', 'EVENING', 'NIGHT', 'CUSTOM'
    )),
    CONSTRAINT chk_dose_log_status CHECK (status IN (
        'UPCOMING', 'TAKEN', 'SKIPPED', 'MISSED'
    )),
    CONSTRAINT uq_dose_log_medicine_date_slot UNIQUE (medicine_id, dose_date, slot)
);

CREATE INDEX idx_dose_log_customer_date
    ON dose_log (customer_id, dose_date);

CREATE INDEX idx_dose_log_member_date
    ON dose_log (member_id, dose_date);

CREATE TABLE reminder_schedule (
    id               UUID PRIMARY KEY,
    medicine_id      UUID NOT NULL REFERENCES schedule_medicine (id),
    customer_id      UUID NOT NULL REFERENCES customers (id),
    dose_log_id      UUID NOT NULL REFERENCES dose_log (id),
    scheduled_at     TIMESTAMPTZ NOT NULL,
    channel          VARCHAR(10) NOT NULL DEFAULT 'PUSH',
    status           VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED',
    notification_id  VARCHAR(200) NULL,
    sent_at          TIMESTAMPTZ NULL,
    delivered_at     TIMESTAMPTZ NULL,
    opened_at        TIMESTAMPTZ NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_reminder_schedule_channel CHECK (channel IN ('PUSH', 'SMS')),
    CONSTRAINT chk_reminder_schedule_status CHECK (status IN (
        'SCHEDULED', 'SENT', 'DELIVERED', 'FAILED', 'CANCELLED'
    )),
    CONSTRAINT uq_reminder_schedule_dose_log UNIQUE (dose_log_id)
);

CREATE INDEX idx_reminder_schedule_scheduled_at
    ON reminder_schedule (scheduled_at);

CREATE INDEX idx_reminder_schedule_status
    ON reminder_schedule (status);

CREATE INDEX idx_reminder_schedule_customer
    ON reminder_schedule (customer_id);

CREATE INDEX idx_reminder_schedule_dose_log
    ON reminder_schedule (dose_log_id);
