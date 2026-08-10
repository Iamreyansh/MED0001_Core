-- EPIC-008 / STORY-004: statutory Schedule H1/X drug register
-- Rollback:
--   DROP TRIGGER IF EXISTS trg_schedule_drug_register_no_delete ON schedule_drug_register_entry;
--   DROP TRIGGER IF EXISTS trg_schedule_drug_register_update_guard ON schedule_drug_register_entry;
--   DROP FUNCTION IF EXISTS schedule_drug_register_reject_delete();
--   DROP FUNCTION IF EXISTS schedule_drug_register_update_guard();
--   DROP INDEX IF EXISTS uq_schedule_drug_register_rx_ref;
--   DROP INDEX IF EXISTS idx_schedule_drug_register_pharmacy_schedule_dispensed;
--   DROP INDEX IF EXISTS idx_schedule_drug_register_retention;
--   DROP TABLE IF EXISTS schedule_drug_register_export_job;
--   DROP TABLE IF EXISTS schedule_drug_register_entry;
-- Notes: Append-only register (DELETE blocked). UPDATE allowed only for is_archived.
--        order_id nullable when walk-in dispense has no linked order.
--        rx_reference_no unique per pharmacy (RX-{YYYY}-{5-digit} seq per pharmacy/year).

CREATE TABLE schedule_drug_register_entry (
    id                   UUID PRIMARY KEY,
    sno                  INTEGER NOT NULL,
    pharmacy_id          UUID NOT NULL REFERENCES pharmacies (id),
    schedule             VARCHAR(2) NOT NULL,
    rx_id                UUID NOT NULL REFERENCES prescription (id),
    rx_reference_no      VARCHAR(32) NOT NULL,
    order_id             UUID NULL REFERENCES orders (id),
    patient_name         VARCHAR(200) NOT NULL,
    patient_age          INTEGER NULL,
    prescriber_name      VARCHAR(200) NOT NULL,
    prescriber_reg_no    VARCHAR(64) NOT NULL,
    drug_name            VARCHAR(200) NOT NULL,
    batch_no             VARCHAR(64) NULL,
    quantity_issued      INTEGER NOT NULL CHECK (quantity_issued > 0),
    unit                 VARCHAR(32) NOT NULL,
    running_balance      INTEGER NOT NULL,
    pharmacy_license_no  VARCHAR(64) NOT NULL,
    dispensed_by_name    VARCHAR(200) NOT NULL,
    dispensed_by_user_id UUID NOT NULL,
    dispensed_at         TIMESTAMPTZ NOT NULL,
    retention_expires_at TIMESTAMPTZ NOT NULL,
    is_archived          BOOLEAN NOT NULL DEFAULT FALSE,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_schedule_drug_register_schedule CHECK (schedule IN ('H1', 'X')),
    CONSTRAINT uq_schedule_drug_register_pharmacy_schedule_sno
        UNIQUE (pharmacy_id, schedule, sno),
    CONSTRAINT uq_schedule_drug_register_pharmacy_rx_ref
        UNIQUE (pharmacy_id, rx_reference_no)
);

CREATE INDEX idx_schedule_drug_register_pharmacy_schedule_dispensed
    ON schedule_drug_register_entry (pharmacy_id, schedule, dispensed_at DESC);

CREATE INDEX idx_schedule_drug_register_drug_name
    ON schedule_drug_register_entry (pharmacy_id, schedule, drug_name);

CREATE INDEX idx_schedule_drug_register_retention
    ON schedule_drug_register_entry (retention_expires_at)
    WHERE is_archived = FALSE;

CREATE TABLE schedule_drug_register_export_job (
    id            UUID PRIMARY KEY,
    pharmacy_id   UUID NOT NULL REFERENCES pharmacies (id),
    schedule      VARCHAR(2) NOT NULL,
    from_date     DATE NOT NULL,
    to_date       DATE NOT NULL,
    status        VARCHAR(20) NOT NULL,
    storage_key   VARCHAR(512),
    row_count     INTEGER,
    requested_by  UUID NOT NULL,
    generated_at  TIMESTAMPTZ,
    expires_at    TIMESTAMPTZ,
    error_message VARCHAR(500),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_schedule_drug_register_export_schedule CHECK (schedule IN ('H1', 'X')),
    CONSTRAINT chk_schedule_drug_register_export_status CHECK (status IN (
        'GENERATING',
        'READY',
        'FAILED'
    ))
);

CREATE INDEX idx_schedule_drug_register_export_created
    ON schedule_drug_register_export_job (created_at DESC);

CREATE OR REPLACE FUNCTION schedule_drug_register_reject_delete()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  RAISE EXCEPTION 'schedule_drug_register_entry is append-only'
    USING ERRCODE = 'check_violation';
END;
$$;

DROP TRIGGER IF EXISTS trg_schedule_drug_register_no_delete ON schedule_drug_register_entry;
CREATE TRIGGER trg_schedule_drug_register_no_delete
  BEFORE DELETE ON schedule_drug_register_entry
  FOR EACH ROW
  EXECUTE FUNCTION schedule_drug_register_reject_delete();

-- Allow UPDATE only when the sole changed column is is_archived (archival job).
CREATE OR REPLACE FUNCTION schedule_drug_register_update_guard()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF NEW.id IS DISTINCT FROM OLD.id
     OR NEW.sno IS DISTINCT FROM OLD.sno
     OR NEW.pharmacy_id IS DISTINCT FROM OLD.pharmacy_id
     OR NEW.schedule IS DISTINCT FROM OLD.schedule
     OR NEW.rx_id IS DISTINCT FROM OLD.rx_id
     OR NEW.rx_reference_no IS DISTINCT FROM OLD.rx_reference_no
     OR NEW.order_id IS DISTINCT FROM OLD.order_id
     OR NEW.patient_name IS DISTINCT FROM OLD.patient_name
     OR NEW.patient_age IS DISTINCT FROM OLD.patient_age
     OR NEW.prescriber_name IS DISTINCT FROM OLD.prescriber_name
     OR NEW.prescriber_reg_no IS DISTINCT FROM OLD.prescriber_reg_no
     OR NEW.drug_name IS DISTINCT FROM OLD.drug_name
     OR NEW.batch_no IS DISTINCT FROM OLD.batch_no
     OR NEW.quantity_issued IS DISTINCT FROM OLD.quantity_issued
     OR NEW.unit IS DISTINCT FROM OLD.unit
     OR NEW.running_balance IS DISTINCT FROM OLD.running_balance
     OR NEW.pharmacy_license_no IS DISTINCT FROM OLD.pharmacy_license_no
     OR NEW.dispensed_by_name IS DISTINCT FROM OLD.dispensed_by_name
     OR NEW.dispensed_by_user_id IS DISTINCT FROM OLD.dispensed_by_user_id
     OR NEW.dispensed_at IS DISTINCT FROM OLD.dispensed_at
     OR NEW.retention_expires_at IS DISTINCT FROM OLD.retention_expires_at
     OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
    RAISE EXCEPTION 'schedule_drug_register_entry is append-only (only is_archived may change)'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_schedule_drug_register_update_guard ON schedule_drug_register_entry;
CREATE TRIGGER trg_schedule_drug_register_update_guard
  BEFORE UPDATE ON schedule_drug_register_entry
  FOR EACH ROW
  EXECUTE FUNCTION schedule_drug_register_update_guard();
