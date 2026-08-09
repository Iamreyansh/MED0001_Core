-- EPIC-012 / STORY-008: financial ledger query/export + append-only enforcement
-- Rollback:
--   DROP TRIGGER IF EXISTS trg_financial_ledger_no_update ON financial_ledger;
--   DROP TRIGGER IF EXISTS trg_financial_ledger_no_delete ON financial_ledger;
--   DROP FUNCTION IF EXISTS financial_ledger_reject_mutation();
--   DROP INDEX IF EXISTS uq_financial_ledger_cod_deposit;
--   DROP INDEX IF EXISTS idx_financial_ledger_created;
--   DROP INDEX IF EXISTS idx_financial_ledger_entry_type_created;
-- Notes: money stays BIGINT paise (V057). Append-only via BEFORE UPDATE/DELETE triggers.
--        COD_DEPOSIT unique (entry_type, reference_id) closes STORY-006 FLAG cheaply.

CREATE OR REPLACE FUNCTION financial_ledger_reject_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  RAISE EXCEPTION 'financial_ledger is append-only'
    USING ERRCODE = 'check_violation';
END;
$$;

DROP TRIGGER IF EXISTS trg_financial_ledger_no_update ON financial_ledger;
CREATE TRIGGER trg_financial_ledger_no_update
  BEFORE UPDATE ON financial_ledger
  FOR EACH ROW
  EXECUTE FUNCTION financial_ledger_reject_mutation();

DROP TRIGGER IF EXISTS trg_financial_ledger_no_delete ON financial_ledger;
CREATE TRIGGER trg_financial_ledger_no_delete
  BEFORE DELETE ON financial_ledger
  FOR EACH ROW
  EXECUTE FUNCTION financial_ledger_reject_mutation();

CREATE INDEX IF NOT EXISTS idx_financial_ledger_created
    ON financial_ledger (created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_financial_ledger_entry_type_created
    ON financial_ledger (entry_type, created_at DESC);

-- STORY-006 FLAG: prevent duplicate COD_DEPOSIT rows for the same deposit reference.
CREATE UNIQUE INDEX IF NOT EXISTS uq_financial_ledger_cod_deposit
    ON financial_ledger (entry_type, reference_id)
    WHERE entry_type = 'COD_DEPOSIT';
