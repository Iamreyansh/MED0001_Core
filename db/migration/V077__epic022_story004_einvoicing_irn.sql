-- EPIC-022 / STORY-004: E-Invoicing IRN records + NIC/GSP call audit log
-- Rollback:
--   DROP TABLE IF EXISTS einvoice_api_call_log;
--   DROP TABLE IF EXISTS einvoice_irn_records;
-- Notes: e_invoicing_enabled already on pharmacies (V016); IF NOT EXISTS for safety.
--   financial_year + document_type support BR-2 uniqueness (seller+buyer+doc+FY+invoice_number).
--   platform_invoice_id has no FK (invoice may be marketplace/POS; avoid cross-epic coupling).

ALTER TABLE pharmacies
    ADD COLUMN IF NOT EXISTS e_invoicing_enabled BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE einvoice_irn_records (
    id UUID PRIMARY KEY,
    pharmacy_id UUID NULL REFERENCES pharmacies (id),
    platform_invoice_id UUID NULL,
    irn VARCHAR(64) NOT NULL,
    ack_number VARCHAR(20) NOT NULL,
    ack_date TIMESTAMPTZ NOT NULL,
    seller_gstin VARCHAR(15) NOT NULL,
    buyer_gstin VARCHAR(15) NOT NULL,
    invoice_number VARCHAR(50) NOT NULL,
    invoice_date DATE NOT NULL,
    document_type VARCHAR(10) NOT NULL DEFAULT 'INV',
    financial_year VARCHAR(7) NOT NULL,
    total_invoice_value DECIMAL(14, 2) NOT NULL,
    qr_code_url TEXT NOT NULL,
    signed_invoice_json TEXT NOT NULL,
    status VARCHAR(10) NOT NULL,
    cancel_reason_code VARCHAR(2) NULL,
    cancel_remark TEXT NULL,
    generated_at TIMESTAMPTZ NOT NULL,
    cancelled_at TIMESTAMPTZ NULL,
    CONSTRAINT einvoice_irn_records_status_chk CHECK (status IN ('ACTIVE', 'CANCELLED')),
    CONSTRAINT einvoice_irn_records_irn_uq UNIQUE (irn),
    CONSTRAINT einvoice_irn_records_doc_uq UNIQUE (
        seller_gstin, buyer_gstin, document_type, financial_year, invoice_number
    )
);

CREATE INDEX idx_einvoice_irn_records_pharmacy
    ON einvoice_irn_records (pharmacy_id);

CREATE INDEX idx_einvoice_irn_records_platform_invoice
    ON einvoice_irn_records (platform_invoice_id);

CREATE TABLE einvoice_api_call_log (
    id UUID PRIMARY KEY,
    api_type VARCHAR(20) NOT NULL,
    request_summary VARCHAR(200) NOT NULL,
    http_status SMALLINT NULL,
    response_status VARCHAR(20) NOT NULL,
    latency_ms INTEGER NOT NULL,
    called_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT einvoice_api_call_log_api_type_chk CHECK (
        api_type IN ('GENERATE_IRN', 'CANCEL_IRN', 'STATUS', 'TOKEN_REFRESH')
    ),
    CONSTRAINT einvoice_api_call_log_response_status_chk CHECK (
        response_status IN ('OK', 'ERROR', 'NOT_FOUND', 'INVALID', 'SKIPPED')
    )
);

CREATE INDEX idx_einvoice_api_call_log_called_at
    ON einvoice_api_call_log (called_at);

CREATE INDEX idx_einvoice_api_call_log_api_type_called_at
    ON einvoice_api_call_log (api_type, called_at);
