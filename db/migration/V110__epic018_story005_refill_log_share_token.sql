-- EPIC-018 / STORY-005: refill_log + schedule_share_token + last_refill_alert_pushed_on
-- Rollback:
--   DROP INDEX IF EXISTS idx_schedule_share_token_customer;
--   DROP INDEX IF EXISTS idx_schedule_share_token_expires;
--   DROP TABLE IF EXISTS schedule_share_token;
--   DROP INDEX IF EXISTS idx_refill_log_customer;
--   DROP INDEX IF EXISTS idx_refill_log_medicine_date;
--   DROP TABLE IF EXISTS refill_log;
--   ALTER TABLE schedule_medicine DROP COLUMN IF EXISTS last_refill_alert_pushed_on;
-- Notes: units_added may be negative for nightly supply decrement audit;
--   positive units_added enforced in API for manual refill. No CHECK > 0.

ALTER TABLE schedule_medicine
    ADD COLUMN last_refill_alert_pushed_on DATE NULL;

CREATE TABLE refill_log (
    id              UUID PRIMARY KEY,
    medicine_id     UUID NOT NULL REFERENCES schedule_medicine (id),
    customer_id     UUID NOT NULL REFERENCES customers (id),
    units_added     INTEGER NOT NULL,
    units_before    INTEGER NOT NULL,
    units_after     INTEGER NOT NULL,
    refill_date     DATE NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_refill_log_medicine_date
    ON refill_log (medicine_id, refill_date);

CREATE INDEX idx_refill_log_customer
    ON refill_log (customer_id);

CREATE TABLE schedule_share_token (
    id              UUID PRIMARY KEY,
    token           VARCHAR(50) NOT NULL,
    customer_id     UUID NOT NULL REFERENCES customers (id),
    member_id       UUID NOT NULL REFERENCES care_circle_member (id),
    expires_at      TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_schedule_share_token UNIQUE (token)
);

CREATE INDEX idx_schedule_share_token_expires
    ON schedule_share_token (expires_at);

CREATE INDEX idx_schedule_share_token_customer
    ON schedule_share_token (customer_id);
