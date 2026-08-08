-- EPIC-011 / STORY-005: delivery zone management
-- Rollback:
--   DROP TABLE IF EXISTS rebalancing_suggestions;
--   DROP INDEX IF EXISTS idx_zones_polygon_gist;
--   DROP INDEX IF EXISTS uq_zones_name_city;
--   ALTER TABLE zones
--     DROP COLUMN IF EXISTS polygon,
--     DROP COLUMN IF EXISTS polygon_geojson,
--     DROP COLUMN IF EXISTS area_sq_km,
--     DROP COLUMN IF EXISTS base_fee,
--     DROP COLUMN IF EXISTS per_km_fee,
--     DROP COLUMN IF EXISTS sla_minutes,
--     DROP COLUMN IF EXISTS min_order_value,
--     DROP COLUMN IF EXISTS free_delivery_threshold,
--     DROP COLUMN IF EXISTS surge_multiplier,
--     DROP COLUMN IF EXISTS is_surge_active,
--     DROP COLUMN IF EXISTS is_serviceable,
--     DROP COLUMN IF EXISTS offline_reason,
--     DROP COLUMN IF EXISTS created_by,
--     DROP COLUMN IF EXISTS updated_at,
--     DROP COLUMN IF EXISTS deleted_at;
-- Notes: Extends existing pharmacy `zones` (no parallel delivery_zones table).
--        Fees stored as DECIMAL rupees per story contract (API returns Rs).
--        Polygon GeoJSON uses [lng, lat] (GeoJSON); STORY-004 geofences stay [lat, lng].

ALTER TABLE zones
    ADD COLUMN IF NOT EXISTS polygon GEOGRAPHY(POLYGON, 4326),
    ADD COLUMN IF NOT EXISTS polygon_geojson JSONB,
    ADD COLUMN IF NOT EXISTS area_sq_km NUMERIC(8, 3),
    ADD COLUMN IF NOT EXISTS base_fee NUMERIC(8, 2) NOT NULL DEFAULT 25.00,
    ADD COLUMN IF NOT EXISTS per_km_fee NUMERIC(8, 2) NOT NULL DEFAULT 5.00,
    ADD COLUMN IF NOT EXISTS sla_minutes INTEGER NOT NULL DEFAULT 30,
    ADD COLUMN IF NOT EXISTS min_order_value NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    ADD COLUMN IF NOT EXISTS free_delivery_threshold NUMERIC(10, 2) NOT NULL DEFAULT 199.00,
    ADD COLUMN IF NOT EXISTS surge_multiplier NUMERIC(4, 2) NOT NULL DEFAULT 1.00,
    ADD COLUMN IF NOT EXISTS is_surge_active BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS is_serviceable BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS offline_reason TEXT,
    ADD COLUMN IF NOT EXISTS created_by UUID,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;

-- Keep pharmacy `active` and delivery `is_serviceable` aligned for existing rows.
UPDATE zones
SET is_serviceable = active,
    area_sq_km = COALESCE(area_sq_km, coverage_area_sqkm, 0),
    updated_at = COALESCE(updated_at, created_at, NOW());

-- Seed WGS84 polygons for existing stub zones (closed rings, lng/lat GeoJSON).
UPDATE zones SET
    polygon = ST_GeogFromText(
        'POLYGON((77.6100 12.9200, 77.6400 12.9200, 77.6400 12.9450, 77.6100 12.9450, 77.6100 12.9200))'
    ),
    polygon_geojson = '{
      "type":"Polygon",
      "coordinates":[[[77.6100,12.9200],[77.6400,12.9200],[77.6400,12.9450],[77.6100,12.9450],[77.6100,12.9200]]]
    }'::jsonb,
    area_sq_km = COALESCE(area_sq_km, coverage_area_sqkm, 7.200),
    coverage_area_sqkm = COALESCE(coverage_area_sqkm, 7.20)
WHERE id = 'a0000001-0000-4000-8000-000000000001'
  AND polygon IS NULL;

UPDATE zones SET
    polygon = ST_GeogFromText(
        'POLYGON((72.8200 18.9500, 72.8600 18.9500, 72.8600 18.9800, 72.8200 18.9800, 72.8200 18.9500))'
    ),
    polygon_geojson = '{
      "type":"Polygon",
      "coordinates":[[[72.8200,18.9500],[72.8600,18.9500],[72.8600,18.9800],[72.8200,18.9800],[72.8200,18.9500]]]
    }'::jsonb,
    area_sq_km = COALESCE(area_sq_km, coverage_area_sqkm, 12.100),
    coverage_area_sqkm = COALESCE(coverage_area_sqkm, 12.10)
WHERE id = 'a0000002-0000-4000-8000-000000000002'
  AND polygon IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_zones_name_city
    ON zones (LOWER(name), LOWER(city))
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_zones_polygon_gist
    ON zones USING GIST (polygon)
    WHERE deleted_at IS NULL AND polygon IS NOT NULL;

CREATE TABLE IF NOT EXISTS rebalancing_suggestions (
    id               UUID PRIMARY KEY,
    from_zone_id     UUID NOT NULL REFERENCES zones (id),
    to_zone_id       UUID NOT NULL REFERENCES zones (id),
    riders_to_move   INTEGER NOT NULL,
    reason           TEXT NOT NULL,
    confidence_pct   NUMERIC(5, 2) NOT NULL,
    suggested_riders JSONB NOT NULL DEFAULT '[]'::jsonb,
    status           VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    applied_by       UUID,
    applied_at       TIMESTAMPTZ,
    expires_at       TIMESTAMPTZ NOT NULL,
    generated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_rebalancing_status
        CHECK (status IN ('PENDING', 'APPLIED', 'DISMISSED', 'EXPIRED')),
    CONSTRAINT chk_rebalancing_riders_to_move
        CHECK (riders_to_move >= 1)
);

CREATE INDEX IF NOT EXISTS idx_rebalancing_suggestions_pending
    ON rebalancing_suggestions (status, expires_at)
    WHERE status = 'PENDING';
