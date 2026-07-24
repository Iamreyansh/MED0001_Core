-- MED0001 Core baseline schema placeholder.
-- Domain tables are added by epic/story migrations (V{epic}_{story}_*.sql).

CREATE TABLE IF NOT EXISTS schema_bootstrap (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS outbox_message (
    id UUID PRIMARY KEY,
    type VARCHAR(128) NOT NULL,
    payload_json TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    published BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_outbox_unpublished ON outbox_message (published, created_at);
