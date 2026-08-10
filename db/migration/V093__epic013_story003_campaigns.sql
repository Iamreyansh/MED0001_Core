-- EPIC-013 / STORY-003: campaigns + timeline + interactions
-- Rollback:
--   DROP INDEX IF EXISTS idx_campaign_interactions_customer_at;
--   DROP TABLE IF EXISTS campaign_interactions;
--   DROP INDEX IF EXISTS idx_campaign_timeline_campaign;
--   DROP TABLE IF EXISTS campaign_timeline;
--   DROP INDEX IF EXISTS idx_campaigns_segment_active;
--   DROP INDEX IF EXISTS idx_campaigns_status_channel;
--   DROP TABLE IF EXISTS campaigns;
-- Notes: money as BIGINT paise (API exposes *_rs / cost-estimate *_paise);
--   channel PUSH|SMS|EMAIL|WHATSAPP; status DRAFT|SCHEDULED|RUNNING|PAUSED|COMPLETED;
--   timeline events CREATED|LAUNCHED|PAUSED|RESUMED|COMPLETED|BUDGET_PAUSED;
--   interactions power 48h conversion attribution; created_by → admin_staff.

CREATE TABLE campaigns (
    id                      UUID PRIMARY KEY,
    name                    VARCHAR(200) NOT NULL,
    channel                 VARCHAR(20) NOT NULL,
    segment_id              UUID NOT NULL REFERENCES segments (id),
    message_template_id     UUID,
    subject                 VARCHAR(200),
    body                    TEXT,
    cta_label               VARCHAR(80),
    cta_link                TEXT,
    scheduled_at            TIMESTAMPTZ,
    launched_at             TIMESTAMPTZ,
    completed_at            TIMESTAMPTZ,
    paused_at               TIMESTAMPTZ,
    estimated_cost_paise    BIGINT,
    budget_cap_paise        BIGINT,
    actual_spend_paise      BIGINT NOT NULL DEFAULT 0,
    sent_count              INTEGER NOT NULL DEFAULT 0,
    delivered_count         INTEGER NOT NULL DEFAULT 0,
    opened_count            INTEGER NOT NULL DEFAULT 0,
    clicked_count           INTEGER NOT NULL DEFAULT 0,
    converted_count         INTEGER NOT NULL DEFAULT 0,
    revenue_attributed_paise BIGINT NOT NULL DEFAULT 0,
    audience_snapshot_count INTEGER,
    status                  VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    created_by              UUID REFERENCES admin_staff (id),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_campaigns_channel CHECK (
        channel IN ('PUSH', 'SMS', 'EMAIL', 'WHATSAPP')
    ),
    CONSTRAINT chk_campaigns_status CHECK (
        status IN ('DRAFT', 'SCHEDULED', 'RUNNING', 'PAUSED', 'COMPLETED')
    )
);

CREATE INDEX idx_campaigns_status_channel
    ON campaigns (status, channel);

CREATE INDEX idx_campaigns_segment_active
    ON campaigns (segment_id)
    WHERE status IN ('DRAFT', 'SCHEDULED', 'RUNNING', 'PAUSED');

CREATE TABLE campaign_timeline (
    id           UUID PRIMARY KEY,
    campaign_id  UUID NOT NULL REFERENCES campaigns (id) ON DELETE CASCADE,
    event        VARCHAR(40) NOT NULL,
    at           TIMESTAMPTZ NOT NULL,
    actor        VARCHAR(64) NOT NULL,
    CONSTRAINT chk_campaign_timeline_event CHECK (
        event IN ('CREATED', 'LAUNCHED', 'PAUSED', 'RESUMED', 'COMPLETED', 'BUDGET_PAUSED')
    )
);

CREATE INDEX idx_campaign_timeline_campaign
    ON campaign_timeline (campaign_id, at ASC);

CREATE TABLE campaign_interactions (
    id             UUID PRIMARY KEY,
    campaign_id    UUID NOT NULL REFERENCES campaigns (id) ON DELETE CASCADE,
    customer_id    UUID NOT NULL REFERENCES customers (id),
    interacted_at  TIMESTAMPTZ NOT NULL,
    interaction    VARCHAR(20) NOT NULL DEFAULT 'DELIVERED',
    CONSTRAINT chk_campaign_interactions_type CHECK (
        interaction IN ('DELIVERED', 'OPENED', 'CLICKED')
    )
);

CREATE INDEX idx_campaign_interactions_customer_at
    ON campaign_interactions (customer_id, interacted_at DESC);
