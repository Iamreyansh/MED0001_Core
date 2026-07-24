# STORY-007: Tax & GST Management

| Field | Value |
|---|---|
| Story ID | EPIC-012/STORY-007 |
| Epic | EPIC-012 - Payments and Finance |
| Title | Tax & GST Management |
| Status | Draft |
| Priority | P1 |
| Estimated Effort | 2 Sprints |
| Last Updated | 2026-07-24 |

---

## Overview

This story covers all Indian tax compliance obligations for the Namma MedMate platform. As an e-commerce operator under the GST Act, the platform must collect TCS (Tax Collected at Source) at 1% of GMV from pharmacy settlements and file GSTR-8 monthly. It must also track TDS under Section 194-O on pharmacy commissions and file quarterly TDS returns. GST at 18% is levied on the platform's own commission income. This module provides a read-only tax liability panel, a filing tracker, and data export capabilities for manual submission to the GSTN portal and TDS filing tools.

---

## User Roles

| Role | Capability |
|---|---|
| `admin_finance` | View tax panel, view filings, generate filing data, mark as filed |
| `admin_compliance` | View tax panel, view filings, mark as filed |
| `admin_super` | All of the above |

---

## Business Rules

| # | Rule |
|---|---|
| BR-001 | Namma MedMate is an **e-commerce operator** under Section 52 of the CGST Act; TCS must be collected at **1% of GMV** (0.5% CGST + 0.5% SGST) from pharmacy payouts each month. |
| BR-002 | **TCS deduction** is applied to each pharmacy settlement before release: `TCS = GMV - 0.01`. This is already implemented in STORY-003; this module tracks and reports it for GSTR-8 filing. |
| BR-003 | **TDS under Section 194-O** applies to pharmacy commissions when a pharmacy's **annual commission exceeds Rs 5 lakh**. TDS rate = 1% (0.75% if PAN provided). |
| BR-004 | **GST on platform commission** = 18% (SAC code 9983 - software/intermediary services). The platform charges GST on its commission invoice to pharmacies; this is tracked as output GST. |
| BR-005 | **GSTR-8** must be filed by the **10th of the following month**. Filing is manual (admin exports data and uploads to GSTN portal); the system tracks due dates and flags overdue filings. |
| BR-006 | The tax module is **read-only** for all roles; no tax amount is mutated through these endpoints. Amounts are derived from settlement and payment data. |
| BR-007 | The **TCS register** maintains a per-pharmacy, per-month record of TCS collected for GSTR-8 reconciliation. It is automatically updated when a settlement is released. |
| BR-008 | All tax data is retained for **7 years** as required by Indian income-tax law. |

---

## API Endpoints

### GET /api/v1/admin/finance/taxes

**Auth:** `Bearer JWT` (admin_finance, admin_compliance, admin_super)  
**Description:** Monthly tax liability panel.

**Query Params:** `?month=2026-07` (defaults to current month if omitted)

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "month": "2026-07",
    "tcs_collected": {
      "total_gmv": 2850000.00,
      "tcs_amount": 28500.00,
      "cgst_component": 14250.00,
      "sgst_component": 14250.00,
      "pharmacies_count": 28
    },
    "tds_194o": {
      "eligible_pharmacies_count": 5,
      "total_commission_eligible": 125000.00,
      "tds_amount": 1250.00,
      "note": "Only pharmacies exceeding Rs 5L annual commission threshold"
    },
    "output_gst_on_commission": {
      "total_commission": 228000.00,
      "gst_rate_pct": 18.0,
      "output_gst": 41040.00,
      "sac_code": "9983"
    },
    "input_gst_claimable": {
      "gateway_fees_with_gst": 15200.00,
      "gst_on_gateway_fees": 2736.00,
      "other_input_gst": 5000.00,
      "total_input_gst": 7736.00
    },
    "net_gst_payable": 33304.00,
    "gstr8_due_date": "2026-08-10",
    "gstr8_status": "PENDING"
  },
  "meta": {}
}
```

---

### GET /api/v1/admin/finance/taxes/filings

**Auth:** `Bearer JWT` (admin_finance, admin_compliance, admin_super)  
**Description:** Tax filings tracker - all obligations with due dates and statuses.

**Query Params:** `?year=2026&status=PENDING|FILED|OVERDUE`

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "filings": [
      {
        "filing_id": "filing_uuid",
        "filing_type": "GSTR-8",
        "period": "2026-07",
        "due_date": "2026-08-10",
        "status": "PENDING",
        "description": "TCS collected from pharmacy settlements in July 2026",
        "tcs_amount": 28500.00,
        "filed_at": null,
        "reference_number": null
      },
      {
        "filing_id": "filing_uuid_2",
        "filing_type": "TDS-194O",
        "period": "Q2-2026",
        "due_date": "2026-10-31",
        "status": "PENDING",
        "description": "TDS on pharmacy commissions for Q2 FY 2026-27",
        "tds_amount": 3800.00,
        "filed_at": null,
        "reference_number": null
      },
      {
        "filing_id": "filing_uuid_3",
        "filing_type": "GSTR-3B",
        "period": "2026-06",
        "due_date": "2026-07-20",
        "status": "FILED",
        "filed_at": "2026-07-18T10:00:00Z",
        "reference_number": "ARN-2026-07-18-XXXXXXXX"
      }
    ]
  },
  "meta": {}
}
```

---

### POST /api/v1/admin/finance/taxes/filings/:filing_id/generate

**Auth:** `Bearer JWT` (admin_finance, admin_compliance, admin_super)  
**Description:** Generate filing data for download. Returns a file for submission to GSTN portal or TDS filing tool.

**Request Body:**
```json
{
  "format": "JSON"
}
```

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "filing_id": "filing_uuid",
    "filing_type": "GSTR-8",
    "period": "2026-07",
    "format": "JSON",
    "download_url": "https://s3.amazonaws.com/medmate-filings/gstr8_2026_07.json",
    "expires_at": "2026-07-25T01:30:00Z",
    "record_count": 28,
    "total_tcs_in_file": 28500.00,
    "generated_at": "2026-07-24T16:00:00Z"
  },
  "meta": {}
}
```

**Errors:**
| Code | HTTP | Meaning |
|---|---|---|
| `FILING_NOT_FOUND` | 404 | filing_id does not exist |
| `FILING_ALREADY_FILED` | 409 | Filing is in FILED state; re-generation blocked |
| `INVALID_FORMAT` | 422 | format not JSON or CSV |
| `DATA_NOT_AVAILABLE` | 422 | Period data not yet available (current month not closed) |

---

### POST /api/v1/admin/finance/taxes/filings/:filing_id/mark-filed

**Auth:** `Bearer JWT` (admin_finance, admin_compliance, admin_super)  
**Description:** Mark a filing as submitted to GSTN / TDS portal.

**Request Body:**
```json
{
  "filed_at": "2026-08-08T14:30:00Z",
  "reference_number": "ARN-2026-08-08-XXXXXXXX",
  "notes": "GSTR-8 for July 2026 filed via GSTN portal."
}
```

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "filing_id": "filing_uuid",
    "filing_type": "GSTR-8",
    "period": "2026-07",
    "status": "FILED",
    "filed_at": "2026-08-08T14:30:00Z",
    "reference_number": "ARN-2026-08-08-XXXXXXXX",
    "marked_by": "admin_uuid"
  },
  "meta": {}
}
```

**Errors:**
| Code | HTTP | Meaning |
|---|---|---|
| `FILING_NOT_FOUND` | 404 | filing_id does not exist |
| `ALREADY_FILED` | 409 | Filing already marked as FILED |
| `REFERENCE_REQUIRED` | 422 | reference_number is mandatory |

---

### GET /api/v1/admin/finance/taxes/tcs-register

**Auth:** `Bearer JWT` (admin_finance, admin_compliance, admin_super)  
**Description:** TCS collected per pharmacy per month - primary data source for GSTR-8 reconciliation.

**Query Params:** `?month=2026-07&pharmacy_id=<uuid>&page=1&limit=50`

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "month": "2026-07",
    "total_tcs": 28500.00,
    "total_gmv": 2850000.00,
    "entries": [
      {
        "pharmacy_id": "pharmacy_uuid",
        "pharmacy_name": "Apollo Pharmacy, Koramangala",
        "gstin": "29AAAAA0000A1Z5",
        "pan": "AAAAA0000A",
        "gmv": 52000.00,
        "tcs_collected": 520.00,
        "cgst_tcs": 260.00,
        "sgst_tcs": 260.00,
        "settlement_ids": ["settlement_uuid_1", "settlement_uuid_2"],
        "month": "2026-07"
      }
    ]
  },
  "meta": {
    "page": 1,
    "limit": 50,
    "total": 28
  }
}
```

---

## Data Models

### TaxFiling

| Field | Type | Nullable | Description |
|---|---|---|---|
| `id` | UUID v4 | No | Primary key |
| `filing_type` | ENUM(`GSTR-8`,`TDS-194O`,`GSTR-1`,`GSTR-3B`) | No | Tax return type |
| `period` | VARCHAR(10) | No | e.g., `2026-07` or `Q2-2026` |
| `due_date` | DATE | No | Statutory due date |
| `status` | ENUM(`PENDING`,`FILED`,`OVERDUE`) | No | Filing status |
| `filed_at` | TIMESTAMPTZ | Yes | When manually marked as filed |
| `reference_number` | VARCHAR(100) | Yes | GSTN/TDS ARN or acknowledgement |
| `notes` | TEXT | Yes | Admin notes |
| `marked_by` | UUID | Yes | FK ? AdminUser |
| `generated_files` | JSONB | Yes | Array of download URLs generated |
| `created_at` | TIMESTAMPTZ | No | Record auto-creation on period close |
| `updated_at` | TIMESTAMPTZ | No | Last update |

### TCSRegister

| Field | Type | Nullable | Description |
|---|---|---|---|
| `id` | UUID v4 | No | Primary key |
| `pharmacy_id` | UUID | No | FK ? Pharmacy |
| `month` | CHAR(7) | No | e.g., `2026-07`; composite unique with pharmacy_id |
| `gstin` | VARCHAR(15) | No | Pharmacy GSTIN |
| `pan` | VARCHAR(10) | No | Pharmacy PAN |
| `gmv` | DECIMAL(14,2) | No | Total GMV for the month |
| `tcs_collected` | DECIMAL(12,2) | No | Total TCS for the month |
| `cgst_tcs` | DECIMAL(12,2) | No | 0.5% CGST component |
| `sgst_tcs` | DECIMAL(12,2) | No | 0.5% SGST component |
| `settlement_ids` | UUID[] | No | Array of settlement IDs contributing |
| `gstr8_filing_id` | UUID | Yes | FK ? TaxFiling (GSTR-8) when included |
| `created_at` | TIMESTAMPTZ | No | Record creation |
| `updated_at` | TIMESTAMPTZ | No | Last update |

---

## Acceptance Criteria

| # | Criterion |
|---|---|
| AC-001 | `GET /admin/finance/taxes?month=2026-07` correctly shows `tcs_collected.tcs_amount = total_gmv - 0.01`. |
| AC-002 | `TCSRegister` is updated automatically when a pharmacy settlement is released; GSTIN and PAN are sourced from the pharmacy profile. |
| AC-003 | `POST /taxes/filings/:id/generate` returns a downloadable JSON or CSV file with all TCS records for the period; the file structure matches GSTR-8 GSTN upload format. |
| AC-004 | `POST /taxes/filings/:id/mark-filed` requires a `reference_number`; omitting it returns HTTP 422 `REFERENCE_REQUIRED`. |
| AC-005 | A filing past its `due_date` with `status = PENDING` automatically shows `status = OVERDUE` in the filings list. |
| AC-006 | `GET /taxes/tcs-register?month=2026-07` returns one entry per pharmacy with their GSTIN, GMV, and CGST/SGST TCS breakdown. |
| AC-007 | Attempting to regenerate a file for a FILED filing returns HTTP 409 `FILING_ALREADY_FILED`. |
| AC-008 | All tax data endpoints are accessible only to `admin_finance`, `admin_compliance`, and `admin_super`; other roles receive HTTP 403. |

---

## Dependencies

| Dependency | Type | Notes |
|---|---|---|
| Pharmacy Settlements (EPIC-012/STORY-003) | Internal | TCS amounts sourced from settlements |
| Pharmacy Management (EPIC-003) | Internal | GSTIN, PAN for TCS register |
| Financial Ledger (EPIC-012/STORY-008) | Internal | Commission and TCS amounts |
| AWS S3 | External | Generated filing file storage |
| Scheduled Job Runner | Internal | Monthly TaxFiling record creation; OVERDUE status update |
| GSTN Portal | External | Manual upload (no direct API integration in v1) |

---

## Notes

- GSTR-8 JSON format must conform to GSTN's official schema for Table 3 (supplies made through the operator). The generated file is validated against the schema before the download URL is created.
- The tax module does not integrate with the GSTN API in v1; all filings are manual uploads by the finance/compliance team.
- TaxFiling records for GSTR-8 are auto-created on the 1st of each month for the previous month (with `status = PENDING`); GSTR-3B and GSTR-1 records are created manually.
- TDS 194-O threshold tracking (Rs 5 lakh annual) requires a rolling 12-month commission sum per pharmacy, updated on each settlement release.
