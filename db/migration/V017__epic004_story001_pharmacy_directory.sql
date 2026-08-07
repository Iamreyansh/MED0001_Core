-- EPIC-004 / STORY-001: pharmacy directory metrics + fuzzy search
-- Rollback: DROP VIEW IF EXISTS pharmacy_directory_view;
--           DROP TABLE IF EXISTS pharmacy_directory_metrics;
--           DROP INDEX IF EXISTS idx_pharmacies_owner_name_trgm;
--           DROP INDEX IF EXISTS idx_pharmacies_business_name_trgm;
--           DROP INDEX IF EXISTS idx_pharmacies_phone_trgm;
--           DROP INDEX IF EXISTS idx_pharmacies_code_trgm;
-- Notes: pg_trgm for fuzzy admin search; metrics cache table (paise) filled by nightly job /
--        order-domain port later. Stub zeros until EPIC-008/STORY-004-002.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE IF NOT EXISTS pharmacy_directory_metrics (
    pharmacy_id       UUID PRIMARY KEY REFERENCES pharmacies (id),
    rating            NUMERIC(3, 2) NOT NULL DEFAULT 0.00,
    review_count      INTEGER NOT NULL DEFAULT 0,
    orders_today      INTEGER NOT NULL DEFAULT 0,
    gmv_today_paise   BIGINT NOT NULL DEFAULT 0,
    fill_rate_pct     NUMERIC(5, 2) NOT NULL DEFAULT 0.00,
    net_payout_paise  BIGINT NOT NULL DEFAULT 0,
    commission_today_paise BIGINT NOT NULL DEFAULT 0,
    metrics_as_of     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_pharmacies_business_name_trgm
    ON pharmacies USING GIN (business_name gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_pharmacies_owner_name_trgm
    ON pharmacies USING GIN (owner_name gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_pharmacies_phone_trgm
    ON pharmacies USING GIN (phone gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_pharmacies_code_trgm
    ON pharmacies USING GIN (code gin_trgm_ops);

-- Read model joining pharmacy + zone + cached daily metrics (avoids N+1 in directory list).
CREATE OR REPLACE VIEW pharmacy_directory_view AS
SELECT
    p.id AS pharmacy_id,
    p.code,
    COALESCE(NULLIF(TRIM(p.business_name), ''), p.name) AS business_name,
    p.owner_name,
    p.phone,
    p.email,
    p.zone_id,
    z.name AS zone_name,
    p.status,
    p.plan,
    p.is_online,
    p.commission_pct,
    p.kyc_submitted_at,
    p.kyc_sla_reset_at,
    p.created_at,
    p.updated_at,
    p.plan_expires_at,
    COALESCE(m.rating, 0.00) AS rating,
    COALESCE(m.review_count, 0) AS review_count,
    COALESCE(m.orders_today, 0) AS orders_today,
    COALESCE(m.gmv_today_paise, 0) AS gmv_today_paise,
    COALESCE(m.fill_rate_pct, 0.00) AS fill_rate_pct,
    COALESCE(m.net_payout_paise, 0) AS net_payout_paise,
    COALESCE(m.commission_today_paise, 0) AS commission_today_paise,
    m.metrics_as_of,
    p.deleted_at
FROM pharmacies p
LEFT JOIN zones z ON z.id = p.zone_id
LEFT JOIN pharmacy_directory_metrics m ON m.pharmacy_id = p.id;
