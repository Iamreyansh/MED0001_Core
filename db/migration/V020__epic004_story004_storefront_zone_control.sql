-- EPIC-004 / STORY-004: storefront & zone control
-- Rollback: DROP TABLE IF EXISTS catalogue_pause;
--           ALTER TABLE pharmacies DROP COLUMN IF EXISTS admin_forced_offline;
--           ALTER TABLE zones DROP COLUMN IF EXISTS city, DROP COLUMN IF EXISTS state,
--             DROP COLUMN IF EXISTS coverage_area_sqkm, DROP COLUMN IF EXISTS created_at;

ALTER TABLE pharmacies
    ADD COLUMN IF NOT EXISTS admin_forced_offline BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE zones
    ADD COLUMN IF NOT EXISTS city VARCHAR(100),
    ADD COLUMN IF NOT EXISTS state VARCHAR(100),
    ADD COLUMN IF NOT EXISTS coverage_area_sqkm DECIMAL(8, 2),
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

UPDATE zones SET
    city = 'Bengaluru',
    state = 'Karnataka',
    coverage_area_sqkm = 8.40
WHERE id = 'a0000001-0000-4000-8000-000000000001' AND city IS NULL;

UPDATE zones SET
    city = 'Mumbai',
    state = 'Maharashtra',
    coverage_area_sqkm = 12.10
WHERE id = 'a0000002-0000-4000-8000-000000000002' AND city IS NULL;

ALTER TABLE zones
    ALTER COLUMN city SET NOT NULL,
    ALTER COLUMN state SET NOT NULL;

CREATE TABLE IF NOT EXISTS catalogue_pause (
    id                 UUID PRIMARY KEY,
    pharmacy_id        UUID NOT NULL REFERENCES pharmacies (id),
    reason             TEXT NOT NULL,
    paused_at          TIMESTAMPTZ NOT NULL,
    auto_resume_at     TIMESTAMPTZ NOT NULL,
    resumed_at         TIMESTAMPTZ,
    items_hidden_count INTEGER NOT NULL,
    paused_by          UUID NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_catalogue_pause_active
    ON catalogue_pause (pharmacy_id)
    WHERE resumed_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_catalogue_pause_auto_resume
    ON catalogue_pause (auto_resume_at)
    WHERE resumed_at IS NULL;
