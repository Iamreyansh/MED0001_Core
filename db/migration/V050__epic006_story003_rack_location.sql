-- EPIC-006 / STORY-003: rack_location physical shelf mapping
-- Rollback: DROP INDEX IF EXISTS idx_pharmacy_product_rack_locations;
--           DROP TABLE IF EXISTS rack_location;
-- Notes: Product↔rack mapping stays on pharmacy_product.rack_locations (TEXT[]).
--        Soft delete via deleted_at; uniqueness scoped to active rows per pharmacy.

CREATE TABLE rack_location (
    id              UUID PRIMARY KEY,
    pharmacy_id     UUID NOT NULL REFERENCES pharmacies (id),
    rack_code       VARCHAR(20) NOT NULL,
    zone_name       VARCHAR(100) NOT NULL,
    description     VARCHAR(300),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at      TIMESTAMPTZ
);

CREATE UNIQUE INDEX uq_rack_location_pharmacy_code_active
    ON rack_location (pharmacy_id, rack_code)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_rack_location_pharmacy
    ON rack_location (pharmacy_id)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_rack_location_pharmacy_zone
    ON rack_location (pharmacy_id, zone_name)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_pharmacy_product_rack_locations
    ON pharmacy_product USING GIN (rack_locations)
    WHERE deleted_at IS NULL;
