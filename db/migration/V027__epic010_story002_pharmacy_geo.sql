-- EPIC-010 / STORY-002: pharmacy lat/lng for Haversine smart-select
-- Rollback: DROP INDEX IF EXISTS idx_pharmacies_lat_lng;
--           ALTER TABLE pharmacies DROP COLUMN IF EXISTS latitude;
--           ALTER TABLE pharmacies DROP COLUMN IF EXISTS longitude;
-- Notes: fill_rate / avg_prep read from pharmacy_directory_metrics + pharmacy_performance_snapshot;
--        no denormalised fill_rate column (hot path joins metrics).

ALTER TABLE pharmacies
    ADD COLUMN IF NOT EXISTS latitude NUMERIC(10, 7),
    ADD COLUMN IF NOT EXISTS longitude NUMERIC(10, 7);

CREATE INDEX IF NOT EXISTS idx_pharmacies_lat_lng
    ON pharmacies (latitude, longitude)
    WHERE latitude IS NOT NULL
      AND longitude IS NOT NULL
      AND deleted_at IS NULL;
