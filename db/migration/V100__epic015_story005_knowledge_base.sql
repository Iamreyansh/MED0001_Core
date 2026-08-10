-- EPIC-015 / STORY-005: knowledge base — canned responses + help articles
-- Rollback:
--   DROP INDEX IF EXISTS idx_support_help_articles_deflection;
--   DROP INDEX IF EXISTS idx_support_help_articles_published;
--   DROP INDEX IF EXISTS uq_support_canned_responses_shortcut;
--   DROP TABLE IF EXISTS support_help_articles;
--   DROP TABLE IF EXISTS support_canned_responses;
-- Notes: shortcut unique among non-deleted; public help filters is_published;
--   ILIKE search on title/body/content (ponytail; tsvector later).

CREATE TABLE support_canned_responses (
    id              UUID PRIMARY KEY,
    title           VARCHAR(200) NOT NULL,
    category        VARCHAR(20) NOT NULL,
    body            TEXT NOT NULL,
    shortcut_key    VARCHAR(50) NOT NULL,
    copy_count      INTEGER NOT NULL DEFAULT 0,
    last_used_at    TIMESTAMPTZ,
    created_by      UUID REFERENCES admin_staff (id),
    deleted_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_support_canned_category CHECK (category IN (
        'ORDER', 'PAYMENT', 'PHARMACY', 'RIDER', 'ACCOUNT', 'PRODUCT', 'OTHER'
    ))
);

CREATE UNIQUE INDEX uq_support_canned_responses_shortcut
    ON support_canned_responses (shortcut_key)
    WHERE deleted_at IS NULL;

CREATE TABLE support_help_articles (
    id                  UUID PRIMARY KEY,
    title               VARCHAR(200) NOT NULL,
    category            VARCHAR(20) NOT NULL,
    content_markdown    TEXT NOT NULL,
    tags                TEXT[] NOT NULL DEFAULT '{}',
    is_published        BOOLEAN NOT NULL DEFAULT FALSE,
    view_count          INTEGER NOT NULL DEFAULT 0,
    deflection_count    INTEGER NOT NULL DEFAULT 0,
    created_by          UUID REFERENCES admin_staff (id),
    deleted_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_support_help_category CHECK (category IN (
        'ORDER', 'PAYMENT', 'PHARMACY', 'RIDER', 'ACCOUNT', 'PRODUCT', 'OTHER'
    ))
);

CREATE INDEX idx_support_help_articles_published
    ON support_help_articles (is_published)
    WHERE deleted_at IS NULL AND is_published = TRUE;

CREATE INDEX idx_support_help_articles_deflection
    ON support_help_articles (deflection_count DESC)
    WHERE deleted_at IS NULL;
