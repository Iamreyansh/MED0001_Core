-- Per-pharmacy Cash / Walk-in supplier so Free GRN can omit distributor_id.
-- Rollback: DELETE FROM distributors WHERE is_system = TRUE;
--           ALTER TABLE distributors DROP COLUMN IF EXISTS is_system;
-- Notes: phone unique is (pharmacy_id, phone) for active rows.

ALTER TABLE distributors
    ADD COLUMN IF NOT EXISTS is_system BOOLEAN NOT NULL DEFAULT FALSE;

CREATE UNIQUE INDEX IF NOT EXISTS uq_distributors_pharmacy_system
    ON distributors (pharmacy_id)
    WHERE is_system = TRUE AND deleted_at IS NULL;

INSERT INTO distributors (
  id, pharmacy_id, firm_name, contact_name, phone, email, gstin,
  drug_licence_number, address, payment_terms_days, credit_limit_paise,
  is_active, is_system, created_at, updated_at, deleted_at
)
SELECT
  gen_random_uuid(),
  p.id,
  'Cash / Walk-in',
  NULL,
  '+919000000000',
  NULL,
  NULL,
  NULL,
  NULL,
  0,
  0,
  TRUE,
  TRUE,
  NOW(),
  NOW(),
  NULL
FROM pharmacies p
WHERE NOT EXISTS (
  SELECT 1 FROM distributors d
   WHERE d.pharmacy_id = p.id
     AND d.is_system = TRUE
     AND d.deleted_at IS NULL
);
