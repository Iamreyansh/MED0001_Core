-- EPIC-011 / STORY-004: real-time rider GPS tracking
-- Rollback: DROP TABLE IF EXISTS geofence_breach_events;
--           DROP TABLE IF EXISTS delivery_geofences;
--           DROP TABLE IF EXISTS rider_locations;
--           DROP EXTENSION IF EXISTS postgis;
-- Notes: PostGIS for GEOGRAPHY polygon + ST_Within; rider_locations retained 30d (purge job);
--        Redis live hash rider_location:{id} TTL 5m is application-side.

CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE rider_locations (
    id            UUID PRIMARY KEY,
    rider_id      UUID NOT NULL REFERENCES riders (id),
    order_id      UUID REFERENCES orders (id),
    lat           NUMERIC(10, 7) NOT NULL,
    lng           NUMERIC(10, 7) NOT NULL,
    accuracy_m    NUMERIC(7, 2),
    speed_kmh     NUMERIC(6, 2),
    heading       NUMERIC(6, 2),
    low_accuracy  BOOLEAN NOT NULL DEFAULT FALSE,
    recorded_at   TIMESTAMPTZ NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_rider_locations_rider_recorded
    ON rider_locations (rider_id, recorded_at DESC);

CREATE INDEX idx_rider_locations_order_recorded
    ON rider_locations (order_id, recorded_at ASC)
    WHERE order_id IS NOT NULL;

CREATE INDEX idx_rider_locations_created
    ON rider_locations (created_at);

CREATE TABLE delivery_geofences (
    id                   UUID PRIMARY KEY,
    zone_id              UUID NOT NULL UNIQUE REFERENCES zones (id),
    polygon              GEOGRAPHY(POLYGON, 4326) NOT NULL,
    polygon_coordinates  JSONB NOT NULL,
    area_sq_km           NUMERIC(8, 3) NOT NULL,
    created_by           UUID NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE geofence_breach_events (
    id           UUID PRIMARY KEY,
    rider_id     UUID NOT NULL REFERENCES riders (id),
    zone_id      UUID NOT NULL REFERENCES zones (id),
    order_id     UUID REFERENCES orders (id),
    breach_lat   NUMERIC(10, 7) NOT NULL,
    breach_lng   NUMERIC(10, 7) NOT NULL,
    alert_sent   BOOLEAN NOT NULL DEFAULT FALSE,
    detected_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_geofence_breach_rider_zone_detected
    ON geofence_breach_events (rider_id, zone_id, detected_at DESC);
