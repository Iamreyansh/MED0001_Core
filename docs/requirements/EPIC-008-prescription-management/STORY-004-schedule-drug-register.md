# STORY-004: Statutory Schedule H1/X Drug Register

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-004 |
| **Epic** | EPIC-008 - Prescription Management |
| **Priority** | P0 |
| **Complexity** | M |
| **Status** | Draft |

---

## Overview

This story implements the digital statutory Schedule H1 and Schedule X drug register required under the Drugs and Cosmetics Act 1940 and accompanying rules. Every time a Schedule H1 or X medicine is dispensed through an Rx-linked order, a register entry is automatically created - recording the prescription reference, patient details, prescriber, quantity dispensed, and the running stock balance for that drug at that pharmacy. The register is append-only (no modifications or deletions), supports per-pharmacy view for pharmacist self-review, and provides admin-facing export in the regulatory CSV format for submission to the Drugs Control Department. Retention periods (H1: 3 years, X: 5 years) are enforced via archival rather than deletion.

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| `admin_compliance` | Full access | View any pharmacy's register, export any pharmacy/period |
| `admin_super` | Full access | Same as admin_compliance |
| `pharmacy_owner` | Read own only | View and export their own pharmacy's register |
| `pharmacy_staff` | Read own only | Same as pharmacy_owner |
| `admin_operations` | Read (no export) | View for operational oversight |

---

## Business Rules

1. **Auto-creation on dispense:** A register entry is automatically created when a pharmacist calls `POST /api/v1/pharmacy/prescriptions/:rx_id/dispense` and the dispensed medicines list contains one or more Schedule H1 or X drugs. Entry creation is synchronous with the dispense action.
2. **Append-only integrity:** Register entries cannot be edited or deleted by any user role. Any attempt to PATCH or DELETE a register entry returns HTTP 405. This immutability is the legal basis for the register's validity.
3. **Running stock balance:** The `running_stock_balance` for each register entry is computed as `previous_balance - qty_issued` at the pharmacy level for that specific drug. The balance is computed at write time and stored on the entry (not recomputed on read) to maintain a reliable historical record.
4. **Separate H1 and X registers:** Schedule H1 and X medicines are maintained in logically separate register views (same underlying table, filtered by `schedule`). The admin toggle between H1 and X is a query parameter, not separate endpoints.
5. **Retention and archival:** H1 entries must be retained for 3 years; X entries for 5 years. After retention period, entries are moved to a cold archive store but are never deleted. Archived entries remain accessible via the same API (archived status visible).
6. **Regulatory export format:** The CSV export format matches the CDSCO Schedule H1/X register template: columns in exact order - S.No, Date, Rx_Reference_No, Patient_Name, Patient_Age, Prescriber_Name, Prescriber_Reg_No, Drug_Name, Batch_No, Quantity_Issued, Running_Balance, Pharmacy_License_No, Dispensed_By.
7. **Pharmacy-scoped visibility:** A pharmacy owner can only view and export entries for their own pharmacy. The admin can query any pharmacy. Cross-pharmacy queries without admin role return HTTP 403.
8. **Retention rules endpoint:** The retention rules endpoint (`GET /api/v1/admin/compliance/drug-register/retention-rules`) is a static/configuration endpoint returning the statutory retention requirements with references to the governing rules.

---

## API Endpoints

### 1. Admin - View Drug Register

```GET /api/v1/admin/compliance/drug-register```

**Authentication:** Bearer JWT - `admin_compliance` | `admin_super` | `admin_operations`
**Rate Limit:** 60 req/min

**Query Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `schedule` | string | Yes | `H1` or `X` |
| `pharmacy_id` | UUID | No | Filter to a specific pharmacy |
| `drug_name` | string | No | Partial match on drug name |
| `from_date` | date | No | Start date (dispensed_at) |
| `to_date` | date | No | End date (dispensed_at) |
| `page` | integer | No | Default 1 |
| `limit` | integer | No | Default 50, max 500 |
| `export` | boolean | No | If true, returns CSV download URL |

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "schedule": "H1",
    "entries": [
      {
        "entry_id": "dreg_01J3KP7VXYZ123",
        "sno": 1,
        "date": "2026-07-24",
        "rx_reference_no": "RX-2026-00451",
        "patient_name": "Ravi Kumar",
        "patient_age": 52,
        "prescriber_name": "Dr. Priya Sharma",
        "prescriber_reg_no": "MH12345",
        "drug_name": "Alprazolam 0.5mg",
        "batch_no": "BX2024011",
        "quantity_issued": 30,
        "running_balance": 470,
        "pharmacy_name": "Sai Medicals, Koramangala",
        "pharmacy_license_no": "KA-PHR-2023-001234",
        "dispensed_by": "Ramesh Pharmacist",
        "dispensed_at": "2026-07-24T08:30:00Z",
        "is_archived": false
      }
    ]
  },
  "meta": {
    "page": 1,
    "limit": 50,
    "total": 1,
    "total_pages": 1,
    "total_qty_issued": 30
  }
}
```

---

### 2. Get Retention Rules

```GET /api/v1/admin/compliance/drug-register/retention-rules```

**Authentication:** Bearer JWT - `admin_compliance` | `admin_super` | `pharmacy_owner`
**Rate Limit:** 10 req/min

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "rules": [
      {
        "schedule": "H1",
        "retention_years": 3,
        "governing_rule": "Drugs and Cosmetics Rules 1945, Rule 65(15)",
        "dcg_guideline_url": "https://cdsco.gov.in/opencms/export/sites/CDSCO_WEB/Pdf-documents/Schedule-H1.pdf",
        "notes": "Register must be maintained in Form-17B and produced on demand by the Inspector"
      },
      {
        "schedule": "X",
        "retention_years": 5,
        "governing_rule": "Drugs and Cosmetics Rules 1945, Rule 65(16)",
        "dcg_guideline_url": "https://cdsco.gov.in/opencms/export/sites/CDSCO_WEB/Pdf-documents/Schedule-X.pdf",
        "notes": "Narcotic/psychotropic drugs; register form specified under NDPS Act provisions"
      }
    ],
    "archival_policy": "Entries are archived (not deleted) after retention period. Archived entries remain accessible via API with is_archived: true."
  }
}
```

---

### 3. Pharmacy - View Own Drug Register

```GET /api/v1/pharmacy/compliance/drug-register```

**Authentication:** Bearer JWT - `pharmacy_owner` | `pharmacy_staff`
**Rate Limit:** 30 req/min

**Query Parameters:** Same as admin endpoint except `pharmacy_id` is auto-resolved from the authenticated user's pharmacy.

**Response `200 OK`:** Same structure as admin register response, scoped to own pharmacy data only.

---

### 4. Admin - Export Drug Register (Full Regulatory CSV)

```POST /api/v1/admin/compliance/drug-register/export```

**Authentication:** Bearer JWT - `admin_compliance` | `admin_super`
**Rate Limit:** 5 req/min

**Request Body:**
```json
{
  "pharmacy_id": "ph_01J3KP7VFFF666",
  "schedule": "H1",
  "from_date": "2026-04-01",
  "to_date": "2026-06-30"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `pharmacy_id` | UUID | Yes | Target pharmacy |
| `schedule` | ENUM | Yes | `H1` or `X` |
| `from_date` | date | Yes | Period start (ISO 8601) |
| `to_date` | date | Yes | Period end (ISO 8601) |

**Response `202 Accepted`:**
```json
{
  "success": true,
  "data": {
    "export_job_id": "exp_01J3KP7VGGG777",
    "status": "GENERATING",
    "estimated_ready_seconds": 15,
    "poll_url": "/api/v1/admin/compliance/drug-register/export/exp_01J3KP7VGGG777"
  }
}
```

**`GET /api/v1/admin/compliance/drug-register/export/:job_id` - Poll status:**
```json
{
  "success": true,
  "data": {
    "export_job_id": "exp_01J3KP7VGGG777",
    "status": "READY",
    "download_url": "https://s3.../exports/drug-register-H1-ph01-Q1-2026.csv?X-Amz-Expires=900",
    "row_count": 47,
    "generated_at": "2026-07-24T09:30:00Z",
    "expires_at": "2026-07-24T09:45:00Z"
  }
}
```

**Errors:**

| Code | HTTP | Description |
|------|------|-------------|
| `PHARMACY_NOT_FOUND` | 404 | Pharmacy ID not found |
| `INVALID_SCHEDULE` | 422 | Schedule must be H1 or X |
| `DATE_RANGE_TOO_LARGE` | 422 | Date range exceeds 1 year |

---

## Data Models

### ScheduleDrugRegisterEntry

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID v4 | PK, NOT NULL | Entry identifier |
| `sno` | integer | NOT NULL | Sequential number per pharmacy per schedule |
| `pharmacy_id` | UUID | FK ? pharmacies.id, NOT NULL | Dispensing pharmacy |
| `schedule` | ENUM | NOT NULL | `H1` or `X` |
| `rx_id` | UUID | FK ? prescriptions.id, NOT NULL | Source prescription |
| `rx_reference_no` | string | NOT NULL, UNIQUE | Human-readable Rx reference (e.g. RX-2026-00451) |
| `order_id` | UUID | FK ? orders.id, NOT NULL | Linked dispensed order |
| `patient_name` | string | NOT NULL | Patient name from prescription |
| `patient_age` | integer | nullable | Patient age if available |
| `prescriber_name` | string | NOT NULL | Doctor name from Rx |
| `prescriber_reg_no` | string | NOT NULL | Doctor's medical council registration number |
| `drug_name` | string | NOT NULL | Dispensed drug name (as on label) |
| `batch_no` | string | nullable | Drug batch number from inventory |
| `quantity_issued` | integer | NOT NULL, > 0 | Quantity dispensed |
| `unit` | string | NOT NULL | `TABLETS`, `ML`, `CAPSULES`, etc. |
| `running_balance` | integer | NOT NULL | Stock balance after this dispense |
| `pharmacy_license_no` | string | NOT NULL | Retail drug license number |
| `dispensed_by_name` | string | NOT NULL | Name of dispensing pharmacist |
| `dispensed_by_user_id` | UUID | FK ? users.id, NOT NULL | User who performed dispense |
| `dispensed_at` | timestamp | NOT NULL | Dispense timestamp |
| `retention_expires_at` | timestamp | NOT NULL | `dispensed_at + 3yr` (H1) or `+ 5yr` (X) |
| `is_archived` | boolean | default false | True after retention archival |
| `created_at` | timestamp | NOT NULL | Entry creation (= dispensed_at) |

---

## Acceptance Criteria

- [ ] **Given** a pharmacist dispenses a Schedule H1 medicine via the Rx dispense endpoint, **when** dispense succeeds, **then** a drug register entry is created synchronously with the correct `running_balance = previous_balance - qty_issued`.
- [ ] **Given** an admin tries to delete a register entry via any HTTP method, **then** the API returns HTTP 405 Method Not Allowed.
- [ ] **Given** `admin_compliance` exports the H1 register for a pharmacy for Q1 2026, **when** the CSV is downloaded, **then** columns appear in regulatory order: S.No, Date, Rx_Reference_No, Patient_Name, Patient_Age, Prescriber_Name, Prescriber_Reg_No, Drug_Name, Batch_No, Quantity_Issued, Running_Balance, Pharmacy_License_No, Dispensed_By.
- [ ] **Given** a pharmacy owner calls `GET /api/v1/pharmacy/compliance/drug-register?schedule=H1`, **when** the response is returned, **then** only entries for their own pharmacy are included (no cross-pharmacy data leakage).
- [ ] **Given** `admin_compliance` calls `GET /api/v1/admin/compliance/drug-register?schedule=X&pharmacy_id=ph_01`, **when** the response is received, **then** only Schedule X entries for that pharmacy are returned.
- [ ] **Given** a Schedule H1 drug register entry was created 3 years and 1 day ago, **when** the archival job runs, **then** the entry's `is_archived` flag is set to `true` and the entry is still queryable via the API.
- [ ] **Given** a Schedule X entry is exactly at 5 years of age, **when** queried, **then** it is still accessible with `is_archived: true` and the `retention_expires_at` date matches.
- [ ] **Given** `pharmacy_staff` calls the admin export endpoint `POST /api/v1/admin/compliance/drug-register/export`, **then** the API returns HTTP 403 Forbidden.

---

## Dependencies

| Dependency | Story / System | Notes |
|------------|---------------|-------|
| EPIC-008 STORY-002 - Pharmacy Rx dispense | Upstream | Dispense event triggers register entry creation |
| EPIC-006 - Pharmacy inventory (POS) | Upstream | Batch number and current stock balance sourced from inventory |
| EPIC-008 STORY-003 - Admin compliance audit | Bidirectional | Register entries linked to audit entries by rx_id |
| EPIC-008 STORY-006 - Compliance filings | Downstream | Register exports used in regulatory filing generation |
| S3 export bucket | Infrastructure | CSV exports stored in S3 with 15-minute signed URL |

---

## Notes

- `rx_reference_no` format: `RX-{YYYY}-{5-digit-seq}` - sequence is per-pharmacy-per-year, reset on 1 Jan.
- `running_balance` is computed at write time by querying the latest entry for the same `pharmacy_id + drug_name + schedule` combination and subtracting `qty_issued`. If no prior entry exists, balance = `opening_stock - qty_issued` (opening stock from inventory).
- The export job is async (queue-backed) because regulatory exports may span years of data. The 15-minute download URL is regenerated on each poll until expiry.
- The `pharmacy_license_no` is pulled from the pharmacy's profile at write time and stored on the entry to preserve the historical value (even if the license is renewed later).
