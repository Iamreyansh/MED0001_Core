# STORY-005: Accounting Integration

| Field | Value |
|-------|-------|
| Story ID | EPIC-022-STORY-005 |
| Epic | EPIC-022 External Integrations |
| Title | Accounting Integration |
| Priority | P2 |
| Status | In Development |
| Role | pharmacy_owner + admin_operations |
| Last Updated | 2026-07-24 |

## Overview

The Accounting Integration story enables pharmacies to sync their sales, purchases, expense, and GST data to Tally or Zoho Books. Zoho Books integration uses OAuth2 with the Zoho Books API to push sales vouchers, purchase vouchers, and GST tax ledger entries. Tally integration generates Tally-compatible XML import files (no direct API - Tally uses file-based data exchange). Both integrations are gated to the Growth plan and above. Auto-sync runs at configured frequency (daily or weekly). Sync status is trackable via a job polling endpoint.

## User Roles

| Role | Access |
|------|--------|
| pharmacy_owner | Configure accounting integration; trigger manual sync |
| admin_operations | View sync status for any pharmacy |

## Business Rules

1. **Plan Gating**: Accounting integration is available to pharmacies on `GROWTH`, `RETAIL_PRO`, or `ENTERPRISE` plans only. Free/Starter pharmacies receive `403 PLAN_UPGRADE_REQUIRED`.
2. **Zoho Books OAuth2**: Zoho Books integration uses the Zoho Accounts OAuth2 authorization code flow. The pharmacy owner authenticates with Zoho and grants the platform access to their Zoho Books organization. Tokens are stored encrypted.
3. **Tally XML Export**: Tally does not have a REST API. The platform generates Tally-compatible XML (Tally XML language format) for import via Tally's Gateway of Tally. The pharmacy downloads the XML and imports it manually into Tally.
4. **Sync Types**: Four sync types are supported: `SALES` (sales invoices/vouchers), `PURCHASES` (purchase invoices), `EXPENSES` (expense entries), `GST` (GST tax ledger entries). Each sync type can be triggered independently.
5. **Async Sync Jobs**: All sync operations are async background jobs. The sync endpoint returns a `job_id` for status polling.
6. **Auto-Sync Schedule**: When `auto_sync_enabled: true`, the platform runs sync at the configured frequency (DAILY at 02:00 IST, WEEKLY on Monday 02:00 IST). The sync type is SALES for daily; SALES + PURCHASES + GST for weekly.
7. **Sync Idempotency**: Each invoice/voucher has a unique `platform_id` that is sent to Zoho/Tally as an external reference. If the same invoice is synced twice, Zoho Books deduplicates by external reference. Tally XML export includes transaction IDs for manual dedup tracking.
8. **Error Handling**: If a sync job fails for specific records (invalid data), those records are logged in `sync_errors` with the reason. Successfully synced records are not retried. The job completes with partial success.
9. **Credential Storage**: Zoho OAuth tokens (access + refresh) are stored encrypted in the database. The platform refreshes access tokens automatically before expiry.
10. **Zoho Organization**: Each pharmacy has one Zoho Books organization ID. The integration setup records this during OAuth2 authorization. A pharmacy cannot sync to multiple Zoho organizations.

## API Endpoints

### POST /api/v1/integrations/accounting/sync

Trigger a sync to the pharmacy's accounting system.

**Auth**: Bearer JWT - `pharmacy_owner`

**Request Body**
```json
{
  "pharmacy_id": "uuid-ph-1",
  "accounting_system": "ZOHO_BOOKS",
  "sync_type": "SALES",
  "period_from": "2026-07-01",
  "period_to": "2026-07-31"
}
```

**Response 202**
```json
{
  "success": true,
  "data": {
    "job_id": "uuid-sync-job-1",
    "accounting_system": "ZOHO_BOOKS",
    "sync_type": "SALES",
    "period_from": "2026-07-01",
    "period_to": "2026-07-31",
    "status": "QUEUED",
    "estimated_records": 148,
    "queued_at": "2026-07-24T10:35:00Z"
  },
  "meta": {}
}
```

**Error Table**

| HTTP Code | Error Code | Condition |
|-----------|-----------|-----------|
| 403 | PLAN_UPGRADE_REQUIRED | Pharmacy on Free/Starter plan |
| 422 | ACCOUNTING_NOT_CONFIGURED | No accounting system connected |
| 422 | INVALID_PERIOD | period_from > period_to |
| 429 | SYNC_IN_PROGRESS | Another sync job is already running |

---

### GET /api/v1/integrations/accounting/sync-status/:job_id

Check the status of a sync job.

**Auth**: Bearer JWT - `pharmacy_owner`, `admin_operations`

**Response 200**
```json
{
  "success": true,
  "data": {
    "job_id": "uuid-sync-job-1",
    "accounting_system": "ZOHO_BOOKS",
    "sync_type": "SALES",
    "status": "COMPLETED",
    "records_processed": 148,
    "records_synced": 146,
    "records_failed": 2,
    "errors": [
      {
        "record_id": "uuid-invoice-42",
        "record_type": "SALES_INVOICE",
        "error_code": "INVALID_CUSTOMER_GSTIN",
        "error_message": "Customer GSTIN 27INVALID123 is not a valid GST number"
      }
    ],
    "started_at": "2026-07-24T10:35:05Z",
    "completed_at": "2026-07-24T10:36:22Z"
  },
  "meta": {}
}
```

---

### GET /api/v1/integrations/accounting/config

Get accounting integration configuration for the pharmacy.

**Auth**: Bearer JWT - `pharmacy_owner`

**Response 200**
```json
{
  "success": true,
  "data": {
    "pharmacy_id": "uuid-ph-1",
    "connected_system": "ZOHO_BOOKS",
    "zoho_organization_id": "60012345678",
    "zoho_organization_name": "Apollo Pharmacy - Bangalore",
    "api_key_status": "CONNECTED",
    "last_sync_at": "2026-07-24T02:00:00Z",
    "last_sync_status": "COMPLETED",
    "auto_sync_enabled": true,
    "sync_frequency": "DAILY",
    "tally_xml_available": false
  },
  "meta": {}
}
```

---

### PATCH /api/v1/integrations/accounting/config

Update accounting integration configuration.

**Auth**: Bearer JWT - `pharmacy_owner`

**Request Body**
```json
{
  "accounting_system": "ZOHO_BOOKS",
  "auto_sync_enabled": true,
  "sync_frequency": "DAILY"
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "accounting_system": "ZOHO_BOOKS",
    "auto_sync_enabled": true,
    "sync_frequency": "DAILY",
    "next_sync_at": "2026-07-25T02:00:00Z",
    "updated_at": "2026-07-24T10:38:00Z"
  },
  "meta": {}
}
```

---

### GET /api/v1/integrations/accounting/export-tally-xml

Export Tally-compatible XML for manual import.

**Auth**: Bearer JWT - `pharmacy_owner`

**Query Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| pharmacy_id | UUID | Yes | Pharmacy ID |
| sync_type | string | Yes | SALES, PURCHASES, GST |
| period_from | string | Yes | ISO date |
| period_to | string | Yes | ISO date |

**Response 200**
```json
{
  "success": true,
  "data": {
    "download_url": "https://s3.amazonaws.com/namma-medmate-exports/tally_xml_uuid-ph-1_20260724.xml?...",
    "file_size_kb": 184,
    "records_count": 148,
    "expires_at": "2026-07-31T10:40:00Z",
    "tally_import_instructions": "Open Tally > Gateway of Tally > Import Data > Vouchers. Select the downloaded XML file."
  },
  "meta": {}
}
```

---

## Data Models

### accounting_integrations

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| pharmacy_id | UUID | FK ? pharmacies |
| accounting_system | VARCHAR(15) | TALLY, ZOHO_BOOKS |
| zoho_organization_id | VARCHAR(20) | Nullable |
| zoho_organization_name | VARCHAR(200) | Nullable |
| zoho_access_token | TEXT | Encrypted; nullable |
| zoho_refresh_token | TEXT | Encrypted; nullable |
| zoho_token_expires_at | TIMESTAMPTZ | Nullable |
| api_key_status | VARCHAR(15) | CONNECTED, DISCONNECTED, ERROR |
| auto_sync_enabled | BOOLEAN | Default false |
| sync_frequency | VARCHAR(10) | DAILY, WEEKLY |
| next_sync_at | TIMESTAMPTZ | Nullable |
| last_sync_at | TIMESTAMPTZ | Nullable |
| last_sync_status | VARCHAR(15) | Nullable |
| created_at | TIMESTAMPTZ | |
| updated_at | TIMESTAMPTZ | |

### accounting_sync_jobs

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| pharmacy_id | UUID | FK ? pharmacies |
| accounting_system | VARCHAR(15) | |
| sync_type | VARCHAR(15) | SALES, PURCHASES, EXPENSES, GST |
| period_from | DATE | |
| period_to | DATE | |
| status | VARCHAR(15) | QUEUED, RUNNING, COMPLETED, FAILED |
| records_processed | INTEGER | |
| records_synced | INTEGER | |
| records_failed | INTEGER | |
| errors | JSONB | Array of error objects |
| triggered_by | VARCHAR(10) | MANUAL, SCHEDULER |
| queued_at | TIMESTAMPTZ | |
| started_at | TIMESTAMPTZ | |
| completed_at | TIMESTAMPTZ | |

## Acceptance Criteria

1. **AC-001**: POST /sync for a Free plan pharmacy returns `403 PLAN_UPGRADE_REQUIRED`.
2. **AC-002**: POST /sync when another sync is in progress returns `429 SYNC_IN_PROGRESS`.
3. **AC-003**: GET /sync-status returns `records_failed: 2` and populates the `errors` array with the specific failed invoice IDs and reasons.
4. **AC-004**: GET /export-tally-xml generates valid Tally XML that Tally can import without errors for SALES sync type.
5. **AC-005**: PATCH /config with `auto_sync_enabled: true, sync_frequency: DAILY` sets `next_sync_at` to the next 02:00 IST.
6. **AC-006**: Zoho Books OAuth2 token refresh happens automatically before expiry; sync jobs do not fail due to expired tokens.
7. **AC-007**: Syncing the same invoice twice to Zoho Books creates only one voucher (external reference deduplication).
8. **AC-008**: GET /config for a pharmacy that has not connected any accounting system returns `connected_system: null` and `api_key_status: DISCONNECTED`.

## Dependencies

| Dependency | Type | Notes |
|-----------|------|-------|
| Zoho Books API | External | Sales and GST voucher creation |
| Zoho Accounts (OAuth2) | External | Authentication for Zoho Books |
| AWS S3 | Storage | Tally XML file export |
| EPIC-012 CRM Subscriptions | Gate | Plan tier check |
| EPIC-006 Pharmacy ERP | Data source | Sales, purchases, GST data |

## Notes

- Tally XML format used: Tally Prime VOUCHER XML (version 1.0). The XML structure uses Tally's standard `<ENVELOPE><BODY><IMPORTDATA>` format. A sample XML template is maintained in the platform codebase.
- Zoho Books API rate limit: 100 API calls per minute per organization. Bulk sync uses the Zoho Books batch create API (up to 25 invoices per request) to stay within limits.
