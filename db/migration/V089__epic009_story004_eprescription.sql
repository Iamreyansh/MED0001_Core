-- EPIC-009 / STORY-004: e-prescription generation and linking
-- Rollback:
--   ALTER TABLE prescription DROP CONSTRAINT IF EXISTS fk_prescription_teleconsult;
--   ALTER TABLE prescription DROP CONSTRAINT IF EXISTS fk_prescription_teleconsult_doctor;
--   DROP INDEX IF EXISTS uq_prescription_rx_id;
--   DROP INDEX IF EXISTS idx_prescription_teleconsult;
--   ALTER TABLE prescription DROP COLUMN IF EXISTS rx_id;
--   ALTER TABLE prescription DROP COLUMN IF EXISTS doctor_id;
--   ALTER TABLE prescription DROP COLUMN IF EXISTS medicines;
--   ALTER TABLE prescription DROP COLUMN IF EXISTS is_advice_only;
--   ALTER TABLE prescription DROP COLUMN IF EXISTS advice_text;
--   ALTER TABLE prescription DROP COLUMN IF EXISTS clinical_notes;
--   ALTER TABLE prescription DROP COLUMN IF EXISTS digital_signature_hash;
--   ALTER TABLE prescription DROP COLUMN IF EXISTS is_verified;
--   ALTER TABLE prescription DROP COLUMN IF EXISTS seal;
--   ALTER TABLE prescription DROP COLUMN IF EXISTS pdf_s3_key;
--   ALTER TABLE prescription DROP COLUMN IF EXISTS pdf_generated_at;
--   DROP SEQUENCE IF EXISTS eprescription_rx_seq;
-- Notes: Reuses type E_PRESCRIPTION, source TELECONSULT, teleconsult_id, expires_at from V080.
--        UUID v4 ids (no rx_ prefixes). RX-ID human format RX-YYYYMMDD-NMM-NNNNNN.

CREATE SEQUENCE IF NOT EXISTS eprescription_rx_seq START WITH 1 INCREMENT BY 1;

ALTER TABLE prescription
    ADD COLUMN IF NOT EXISTS rx_id VARCHAR(32) NULL,
    ADD COLUMN IF NOT EXISTS doctor_id UUID NULL,
    ADD COLUMN IF NOT EXISTS medicines JSONB NULL,
    ADD COLUMN IF NOT EXISTS is_advice_only BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS advice_text TEXT NULL,
    ADD COLUMN IF NOT EXISTS clinical_notes TEXT NULL,
    ADD COLUMN IF NOT EXISTS digital_signature_hash VARCHAR(64) NULL,
    ADD COLUMN IF NOT EXISTS is_verified BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS seal VARCHAR(32) NULL,
    ADD COLUMN IF NOT EXISTS pdf_s3_key VARCHAR(512) NULL,
    ADD COLUMN IF NOT EXISTS pdf_generated_at TIMESTAMPTZ NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_prescription_rx_id
    ON prescription (rx_id)
    WHERE rx_id IS NOT NULL AND deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_prescription_teleconsult
    ON prescription (teleconsult_id)
    WHERE teleconsult_id IS NOT NULL AND deleted_at IS NULL;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'fk_prescription_teleconsult'
  ) THEN
    ALTER TABLE prescription
      ADD CONSTRAINT fk_prescription_teleconsult
      FOREIGN KEY (teleconsult_id) REFERENCES consults (id);
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'fk_prescription_teleconsult_doctor'
  ) THEN
    ALTER TABLE prescription
      ADD CONSTRAINT fk_prescription_teleconsult_doctor
      FOREIGN KEY (doctor_id) REFERENCES teleconsult_doctors (id);
  END IF;
END $$;
