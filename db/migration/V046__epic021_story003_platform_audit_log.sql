-- EPIC-021 / STORY-003: platform audit log (enrich existing audit_log)
-- Rollback:
--   DROP TRIGGER IF EXISTS trg_audit_log_no_delete ON audit_log;
--   DROP TRIGGER IF EXISTS trg_audit_log_no_update ON audit_log;
--   DROP TRIGGER IF EXISTS trg_audit_log_sync_insert ON audit_log;
--   DROP FUNCTION IF EXISTS audit_log_block_mutation();
--   DROP FUNCTION IF EXISTS audit_log_sync_on_insert();
--   DROP TABLE IF EXISTS audit_export_job;
--   ALTER TABLE audit_log DROP COLUMN IF EXISTS archived_at, DROP COLUMN IF EXISTS timestamp,
--     DROP COLUMN IF EXISTS user_agent, DROP COLUMN IF EXISTS metadata, DROP COLUMN IF EXISTS after_state,
--     DROP COLUMN IF EXISTS before_state, DROP COLUMN IF EXISTS resource_id, DROP COLUMN IF EXISTS resource_type,
--     DROP COLUMN IF EXISTS actor_type, DROP COLUMN IF EXISTS actor_name;
-- Notes: keep table name audit_log (ponytail vs story audit_logs). entity_*/payload/created_at retained
-- for pharmacy/catalogue writers. Immutability blocks UPDATE/DELETE except archival stamp on archived_at.

ALTER TABLE audit_log
    ADD COLUMN IF NOT EXISTS actor_name VARCHAR(100),
    ADD COLUMN IF NOT EXISTS actor_type VARCHAR(15) NOT NULL DEFAULT 'ADMIN',
    ADD COLUMN IF NOT EXISTS resource_type VARCHAR(50),
    ADD COLUMN IF NOT EXISTS resource_id UUID,
    ADD COLUMN IF NOT EXISTS before_state JSONB,
    ADD COLUMN IF NOT EXISTS after_state JSONB,
    ADD COLUMN IF NOT EXISTS metadata JSONB,
    ADD COLUMN IF NOT EXISTS user_agent TEXT,
    ADD COLUMN IF NOT EXISTS "timestamp" TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS archived_at TIMESTAMPTZ;

-- Backfill from legacy columns / payload shape used by settings appender.
UPDATE audit_log
SET resource_type = COALESCE(resource_type, entity_type),
    resource_id = COALESCE(resource_id, entity_id),
    "timestamp" = COALESCE("timestamp", created_at),
    actor_name = COALESCE(
        NULLIF(TRIM(actor_name), ''),
        NULLIF(TRIM(payload->>'actor_name'), ''),
        'unknown'),
    actor_type = COALESCE(NULLIF(TRIM(actor_type), ''), 'ADMIN'),
    before_state = COALESCE(before_state, payload->'before'),
    after_state = COALESCE(
        after_state,
        CASE
            WHEN payload ? 'after' THEN payload->'after'
            WHEN payload ? 'before' THEN NULL
            ELSE payload
        END),
    ip_address = COALESCE(ip_address, '0.0.0.0'::inet);

ALTER TABLE audit_log
    ALTER COLUMN actor_name SET DEFAULT 'unknown',
    ALTER COLUMN actor_name SET NOT NULL,
    ALTER COLUMN resource_type SET NOT NULL,
    ALTER COLUMN "timestamp" SET DEFAULT NOW(),
    ALTER COLUMN "timestamp" SET NOT NULL,
    ALTER COLUMN ip_address SET DEFAULT '0.0.0.0',
    ALTER COLUMN ip_address SET NOT NULL,
    ALTER COLUMN entity_id DROP NOT NULL;

ALTER TABLE audit_log DROP CONSTRAINT IF EXISTS chk_audit_log_actor_type;
ALTER TABLE audit_log
    ADD CONSTRAINT chk_audit_log_actor_type
        CHECK (actor_type IN ('ADMIN', 'SYSTEM', 'AUTOMATION'));

CREATE INDEX IF NOT EXISTS idx_audit_log_actor_id ON audit_log (actor_id);
CREATE INDEX IF NOT EXISTS idx_audit_log_action ON audit_log (action);
CREATE INDEX IF NOT EXISTS idx_audit_log_resource_type ON audit_log (resource_type);
CREATE INDEX IF NOT EXISTS idx_audit_log_resource_id ON audit_log (resource_id);
CREATE INDEX IF NOT EXISTS idx_audit_log_timestamp ON audit_log ("timestamp");
CREATE INDEX IF NOT EXISTS idx_audit_log_actor_type ON audit_log (actor_type);

CREATE OR REPLACE FUNCTION audit_log_sync_on_insert()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.resource_type IS NULL OR BTRIM(NEW.resource_type) = '' THEN
        NEW.resource_type := COALESCE(NEW.entity_type, 'unknown');
    END IF;
    IF NEW.entity_type IS NULL OR BTRIM(NEW.entity_type) = '' THEN
        NEW.entity_type := NEW.resource_type;
    END IF;
    IF NEW.resource_id IS NULL THEN
        NEW.resource_id := NEW.entity_id;
    END IF;
    IF NEW.entity_id IS NULL THEN
        NEW.entity_id := NEW.resource_id;
    END IF;
    IF NEW."timestamp" IS NULL THEN
        NEW."timestamp" := COALESCE(NEW.created_at, NOW());
    END IF;
    IF NEW.created_at IS NULL THEN
        NEW.created_at := NEW."timestamp";
    END IF;
    IF NEW.actor_name IS NULL OR BTRIM(NEW.actor_name) = '' THEN
        NEW.actor_name := COALESCE(NULLIF(BTRIM(NEW.payload->>'actor_name'), ''), 'unknown');
    END IF;
    IF NEW.actor_type IS NULL OR BTRIM(NEW.actor_type) = '' THEN
        NEW.actor_type := 'ADMIN';
    END IF;
    IF NEW.ip_address IS NULL THEN
        NEW.ip_address := '0.0.0.0'::inet;
    END IF;
    IF NEW.payload IS NULL THEN
        NEW.payload := '{}'::jsonb;
    END IF;
    IF NEW.before_state IS NULL AND NEW.payload ? 'before' THEN
        NEW.before_state := NEW.payload->'before';
    END IF;
    IF NEW.after_state IS NULL THEN
        IF NEW.payload ? 'after' THEN
            NEW.after_state := NEW.payload->'after';
        ELSIF NOT (NEW.payload ? 'before') AND NEW.payload <> '{}'::jsonb THEN
            NEW.after_state := NEW.payload;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_audit_log_sync_insert ON audit_log;
CREATE TRIGGER trg_audit_log_sync_insert
    BEFORE INSERT ON audit_log
    FOR EACH ROW
    EXECUTE FUNCTION audit_log_sync_on_insert();

-- Append-only: block DELETE and non-archival UPDATE (archived_at stamp only).
CREATE OR REPLACE FUNCTION audit_log_block_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'audit_log is append-only: DELETE not allowed';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
        OR NEW.actor_id IS DISTINCT FROM OLD.actor_id
        OR NEW.actor_name IS DISTINCT FROM OLD.actor_name
        OR NEW.actor_role IS DISTINCT FROM OLD.actor_role
        OR NEW.actor_type IS DISTINCT FROM OLD.actor_type
        OR NEW.action IS DISTINCT FROM OLD.action
        OR NEW.resource_type IS DISTINCT FROM OLD.resource_type
        OR NEW.resource_id IS DISTINCT FROM OLD.resource_id
        OR NEW.entity_type IS DISTINCT FROM OLD.entity_type
        OR NEW.entity_id IS DISTINCT FROM OLD.entity_id
        OR NEW.before_state IS DISTINCT FROM OLD.before_state
        OR NEW.after_state IS DISTINCT FROM OLD.after_state
        OR NEW.metadata IS DISTINCT FROM OLD.metadata
        OR NEW.payload IS DISTINCT FROM OLD.payload
        OR NEW.ip_address IS DISTINCT FROM OLD.ip_address
        OR NEW.user_agent IS DISTINCT FROM OLD.user_agent
        OR NEW."timestamp" IS DISTINCT FROM OLD."timestamp"
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'audit_log is append-only: UPDATE not allowed';
    END IF;
    -- ponytail: allow archival stamp only (Glacier stub marks archived_at).
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_audit_log_no_update ON audit_log;
CREATE TRIGGER trg_audit_log_no_update
    BEFORE UPDATE ON audit_log
    FOR EACH ROW
    EXECUTE FUNCTION audit_log_block_mutation();

DROP TRIGGER IF EXISTS trg_audit_log_no_delete ON audit_log;
CREATE TRIGGER trg_audit_log_no_delete
    BEFORE DELETE ON audit_log
    FOR EACH ROW
    EXECUTE FUNCTION audit_log_block_mutation();

CREATE TABLE IF NOT EXISTS audit_export_job (
    id           UUID PRIMARY KEY,
    status       VARCHAR(20) NOT NULL,
    filters      JSONB NOT NULL DEFAULT '{}'::jsonb,
    download_url TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ,
    CONSTRAINT chk_audit_export_job_status
        CHECK (status IN ('QUEUED', 'PROCESSING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_audit_export_job_created ON audit_export_job (created_at DESC);
