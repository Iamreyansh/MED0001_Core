-- EPIC-013 / STORY-002: banners (Banner CMS)
-- Rollback:
--   DROP INDEX IF EXISTS idx_banners_valid_until_live;
--   DROP INDEX IF EXISTS idx_banners_placement_priority;
--   DROP TABLE IF EXISTS banners;
-- Notes: placements HOME_TOP|HOME_MID|CATEGORY|OFFERS;
--   link_type CATEGORY|PHARMACY|COUPON|EXTERNAL_URL|TELECONSULT;
--   money N/A; created_by → admin_staff; impressions/clicks counters;
--   CTR computed on read (clicks/impressions*100); soft-delete N/A (hard delete).

CREATE TABLE banners (
    id              UUID PRIMARY KEY,
    headline        VARCHAR(120) NOT NULL,
    sub_text        VARCHAR(200),
    image_url       TEXT NOT NULL,
    placement       VARCHAR(20) NOT NULL,
    link_type       VARCHAR(20) NOT NULL,
    link_value      TEXT NOT NULL,
    theme_color     VARCHAR(7),
    is_live         BOOLEAN NOT NULL DEFAULT TRUE,
    valid_from      TIMESTAMPTZ NOT NULL,
    valid_until     TIMESTAMPTZ NOT NULL,
    priority        INTEGER NOT NULL DEFAULT 100,
    impressions     BIGINT NOT NULL DEFAULT 0,
    clicks          BIGINT NOT NULL DEFAULT 0,
    created_by      UUID REFERENCES admin_staff (id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_banners_placement CHECK (
        placement IN ('HOME_TOP', 'HOME_MID', 'CATEGORY', 'OFFERS')
    ),
    CONSTRAINT chk_banners_link_type CHECK (
        link_type IN ('CATEGORY', 'PHARMACY', 'COUPON', 'EXTERNAL_URL', 'TELECONSULT')
    ),
    CONSTRAINT chk_banners_dates CHECK (valid_from <= valid_until)
);

CREATE INDEX idx_banners_placement_priority
    ON banners (placement, priority ASC, created_at ASC);

CREATE INDEX idx_banners_valid_until_live
    ON banners (valid_until)
    WHERE is_live = TRUE;
