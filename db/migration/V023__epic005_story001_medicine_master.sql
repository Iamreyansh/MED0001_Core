-- EPIC-005 / STORY-001: medicine_master + hsn_reference + FTS/trgm
-- Rollback: DROP TABLE IF EXISTS medicine_master; DROP TABLE IF EXISTS hsn_reference;
-- Notes: money as BIGINT paise; uniqueness (salt, manufacturer, form, pack_size, pack_unit);
--        soft-delete not used (ban via is_banned); pg_trgm already from V017

CREATE TABLE hsn_reference (
    hsn_code    CHAR(8) PRIMARY KEY,
    description TEXT NOT NULL,
    chapter     SMALLINT NOT NULL CHECK (chapter IN (29, 30, 90))
);

INSERT INTO hsn_reference (hsn_code, description, chapter) VALUES
    ('30041010', 'Penicillins and derivatives, not for retail sale', 30),
    ('30041090', 'Other penicillins / combinations for retail sale', 30),
    ('30042019', 'Other antibiotics for retail sale', 30),
    ('30042061', 'Erythromycin and derivatives', 30),
    ('30043000', 'Hormones / corticosteroids medicaments', 30),
    ('30045010', 'Vitamin B1/B6/B12 preparations', 30),
    ('30045020', 'Vitamin C preparations', 30),
    ('30045090', 'Other vitamins and minerals', 30),
    ('30049011', 'Ayurvedic / Unani medicaments', 30),
    ('30049014', 'Homeopathic medicaments', 30),
    ('30049029', 'Analgesics / antipyretics / anti-inflammatory', 30),
    ('30049039', 'Antacids / anti-ulcerants', 30),
    ('30049069', 'Other medicaments for retail sale', 30),
    ('30049099', 'Other pharmaceutical products NES', 30),
    ('30051010', 'Adhesive dressings', 30),
    ('30059090', 'Other wadding / gauze / bandages', 30),
    ('30061010', 'Sterile surgical catgut', 30),
    ('29333919', 'Other heterocyclic compounds with N hetero-atom (APIs)', 29),
    ('29339900', 'Other heterocyclic compounds', 29),
    ('29350090', 'Other sulphonamides', 29),
    ('29362100', 'Vitamins A and derivatives (bulk)', 29),
    ('29362200', 'Vitamin B1 and derivatives (bulk)', 29),
    ('29362300', 'Vitamin B2 and derivatives (bulk)', 29),
    ('29362400', 'D- or DL-pantothenic acid (bulk)', 29),
    ('29362500', 'Vitamin B6 and derivatives (bulk)', 29),
    ('29362600', 'Vitamin B12 and derivatives (bulk)', 29),
    ('29362700', 'Vitamin C and derivatives (bulk)', 29),
    ('29362800', 'Vitamin E and derivatives (bulk)', 29),
    ('29362900', 'Other vitamins (bulk)', 29),
    ('90183100', 'Syringes with/without needles', 90),
    ('90183910', 'Catheters', 90),
    ('90183990', 'Other needles / catheters / cannulae', 90),
    ('90189019', 'Other instruments and appliances for medical use', 90),
    ('90192010', 'Ozone therapy / oxygen therapy apparatus', 90),
    ('90211000', 'Orthopaedic / fracture appliances', 90),
    ('90213100', 'Artificial joints', 90),
    ('90214000', 'Hearing aids', 90),
    ('90219090', 'Other orthopaedic appliances', 90)
ON CONFLICT (hsn_code) DO NOTHING;

CREATE TABLE medicine_master (
    id                     UUID PRIMARY KEY,
    name                   VARCHAR(255) NOT NULL,
    salt_composition       TEXT NOT NULL,
    manufacturer           VARCHAR(200) NOT NULL,
    category_id            UUID NOT NULL REFERENCES medicine_category (id),
    form                   VARCHAR(32) NOT NULL,
    pack_size              NUMERIC(8, 2) NOT NULL CHECK (pack_size > 0),
    pack_unit              VARCHAR(32) NOT NULL,
    schedule               VARCHAR(8) NOT NULL CHECK (schedule IN ('OTC', 'H', 'H1', 'X')),
    hsn_code               CHAR(8) NOT NULL REFERENCES hsn_reference (hsn_code),
    gst_pct                SMALLINT NOT NULL CHECK (gst_pct IN (5, 12, 18)),
    mrp_paise              BIGINT NOT NULL CHECK (mrp_paise > 0),
    mrp_ceiling_paise      BIGINT NULL CHECK (mrp_ceiling_paise IS NULL OR mrp_ceiling_paise > 0),
    is_rx_only             BOOLEAN NOT NULL,
    is_banned              BOOLEAN NOT NULL DEFAULT FALSE,
    ban_reason             TEXT,
    monthly_demand         INTEGER NOT NULL DEFAULT 0,
    mapped_pharmacy_count  INTEGER NOT NULL DEFAULT 0,
    substitutes            UUID[] NOT NULL DEFAULT '{}',
    description            TEXT,
    created_by             UUID,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    search_tsv             tsvector GENERATED ALWAYS AS (
        to_tsvector(
            'english',
            coalesce(name, '') || ' ' ||
            coalesce(salt_composition, '') || ' ' ||
            coalesce(manufacturer, '') || ' ' ||
            coalesce(hsn_code, '') || ' ' ||
            coalesce(description, '')
        )
    ) STORED,
    CONSTRAINT uq_medicine_master_identity
        UNIQUE (salt_composition, manufacturer, form, pack_size, pack_unit),
    CONSTRAINT chk_medicine_master_form CHECK (form IN (
        'TABLET', 'CAPSULE', 'SYRUP', 'INJECTION', 'OINTMENT', 'DROPS',
        'INHALER', 'PATCH', 'POWDER', 'SUPPOSITORY', 'OTHER'
    )),
    CONSTRAINT chk_medicine_master_pack_unit CHECK (pack_unit IN (
        'TABLET', 'CAPSULE', 'ML', 'MG', 'G', 'STRIP',
        'VIAL', 'AMPOULE', 'SACHET', 'TUBE', 'BOTTLE'
    ))
);

CREATE INDEX idx_medicine_master_category ON medicine_master (category_id);
CREATE INDEX idx_medicine_master_schedule ON medicine_master (schedule);
CREATE INDEX idx_medicine_master_banned ON medicine_master (is_banned);
CREATE INDEX idx_medicine_master_created ON medicine_master (created_at DESC);
CREATE INDEX idx_medicine_master_search_tsv ON medicine_master USING GIN (search_tsv);
CREATE INDEX idx_medicine_master_name_trgm ON medicine_master USING GIN (name gin_trgm_ops);
CREATE INDEX idx_medicine_master_salt_trgm ON medicine_master USING GIN (salt_composition gin_trgm_ops);
