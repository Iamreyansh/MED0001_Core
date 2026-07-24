# STORY-003: Admin Rx Compliance Audit

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-003 |
| **Epic** | EPIC-008 - Prescription Management |
| **Priority** | P0 |
| **Complexity** | L |
| **Status** | Draft |

---

## Overview

This story defines the compliance audit interface used by `admin_compliance` personnel to review, verify, and flag prescriptions dispensed through the platform - with particular focus on Schedule H, H1, and X medicines. Every dispensed Rx on the platform is subject to audit, but Schedule H1 and X dispenses require a mandatory admin audit within 24 hours of dispensing. The audit workflow supports a structured checklist (doctor registration check, quantity appropriateness, duplicate Rx detection, schedule classification), with actions to mark a prescription as verified or flag it for investigation. Aggregate compliance statistics are surfaced for dashboard reporting and regulatory submissions.

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| `admin_compliance` | Full access | View audit queue, verify, flag, export, view statistics |
| `admin_super` | Full access | Same as admin_compliance |
| `admin_operations` | Read-only | View audit queue for operational oversight |
| `customer` | None | Cannot access admin compliance |
| `pharmacy_owner` | None | Cannot access admin compliance audit |

---

## Business Rules

1. **Mandatory 24-hour audit for Schedule H1 and X:** Any dispense of a Schedule H1 or X medicine must be audited by `admin_compliance` within 24 hours of the `dispensed_at` timestamp. Prescriptions not audited within this window are escalated to `OVERDUE_AUDIT` status and trigger an alert to the compliance team.
2. **Duplicate Rx detection:** The system automatically checks for the same `patient_name` + `drug_name` + `quantity` within a 30-day rolling window. If a match is found, the audit entry is pre-flagged with `POSSIBLE_DUPLICATE_RX` in the verification checklist.
3. **OCR-assisted pre-fill:** The compliance audit detail view uses OCR extraction results (`medicines_extracted`, `doctor_name`, `prescription_date`) to pre-populate the verification checklist, reducing manual effort. Pre-filled fields are clearly labeled as "OCR extracted" vs "manually entered."
4. **Verification actions are append-only:** All audit actions (verify, flag, notes) are stored in an immutable audit log. No action can be edited or deleted after it is committed.
5. **Flagging escalates to compliance team:** When a prescription is flagged, the `admin_compliance` team receives a notification and the flag appears in the compliance activity log. Severity levels `MEDIUM` and `HIGH` trigger an immediate email alert to the Head of Compliance.
6. **Export for regulatory submission:** The audit queue can be exported as CSV with all required fields (Rx ID, patient name, drug name, schedule, pharmacy, dispense date, verification outcome). This CSV is the primary input for the statutory drug register export.
7. **Admin cannot view raw prescription content for non-compliance orders:** `admin_compliance` can view the rendered prescription image only for compliance audit purposes. `admin_operations` and `admin_finance` cannot view prescription images.
8. **Compliance rate calculation:** `compliance_rate_pct` = `(verified_count / total_auditable) - 100` for Schedule H1 + X prescriptions in the selected date range.

---

## API Endpoints

### 1. List Admin Rx Audit Queue

```GET /api/v1/admin/prescriptions```

**Authentication:** Bearer JWT - `admin_compliance` | `admin_super` | `admin_operations`
**Rate Limit:** 60 req/min

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `schedule` | string | `ALL` | `H`, `H1`, `X`, `ALL` |
| `status` | string | `AWAITING_AUDIT` | `AWAITING_AUDIT`, `FLAGGED`, `VERIFIED`, `ALL` |
| `source` | string | all | `DIGITAL`, `UPLOADED` |
| `from_date` | date | - | ISO 8601 date (dispensed_at filter start) |
| `to_date` | date | - | ISO 8601 date (dispensed_at filter end) |
| `search` | string | - | Search by Rx ID, patient name, doctor name |
| `pharmacy_id` | UUID | - | Filter by pharmacy |
| `page` | integer | 1 | Pagination |
| `limit` | integer | 20 | Items per page (max 200) |
| `export` | boolean | false | If true, returns CSV download URL instead |

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "kpis": {
      "awaiting_audit": 23,
      "flagged": 3,
      "schedule_h1_x_count": 18,
      "verified_today": 41,
      "compliance_rate_pct": 94.6
    },
    "prescriptions": [
      {
        "rx_id": "rx_01J3KP7VXYZ123",
        "audit_status": "AWAITING_AUDIT",
        "is_overdue": false,
        "hours_since_dispense": 3.2,
        "schedule": "H1",
        "patient_name": "Ravi Kumar",
        "doctor_name": "Dr. Priya Sharma",
        "doctor_verified": true,
        "pharmacy_name": "Sai Medicals, Koramangala",
        "dispensed_at": "2026-07-24T04:10:00Z",
        "audit_deadline": "2026-07-25T04:10:00Z",
        "possible_duplicate": false,
        "source": "UPLOADED"
      }
    ]
  },
  "meta": {
    "page": 1,
    "limit": 20,
    "total": 23,
    "total_pages": 2
  }
}
```

---

### 2. Get Rx Audit Detail

```GET /api/v1/admin/prescriptions/:rx_id```

**Authentication:** Bearer JWT - `admin_compliance` | `admin_super`
**Rate Limit:** 60 req/min

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "rx_id": "rx_01J3KP7VXYZ123",
    "audit_status": "AWAITING_AUDIT",
    "file_url": "https://s3.../signed-url",
    "verification_checklist": {
      "doctor_registered": { "status": "VERIFIED", "method": "NMC_REGISTRY", "checked_at": "2026-07-24T05:00:00Z" },
      "quantity_appropriate": { "status": "PENDING", "note": null },
      "not_duplicate_rx": { "status": "CLEAR", "duplicate_found": false },
      "schedule_check": { "schedule": "H1", "status": "FLAGGED", "note": "Quantity exceeds standard 30-day supply" }
    },
    "patient": {
      "name": "Ravi Kumar",
      "age": 52,
      "order_count": 15
    },
    "doctor": {
      "name": "Dr. Priya Sharma",
      "qualification": "MBBS MD",
      "registration_no": "MH12345",
      "specialty": "Endocrinology",
      "verified": true,
      "blacklisted": false
    },
    "order_context": {
      "order_id": "ord_01J3KP7VDEF789",
      "order_number": "ORD-20260724-00123",
      "pharmacy_name": "Sai Medicals",
      "dispensed_at": "2026-07-24T08:30:00Z",
      "medicines_dispensed": [
        { "name": "Alprazolam 0.5mg", "quantity": 90, "schedule": "H1" }
      ]
    },
    "audit_history": [],
    "possible_duplicate": false
  }
}
```

---

### 3. Verify Prescription

```POST /api/v1/admin/prescriptions/:rx_id/verify```

**Authentication:** Bearer JWT - `admin_compliance` | `admin_super`
**Rate Limit:** 30 req/min

**Request Body:**
```json
{
  "verified": true,
  "flag_reason": null,
  "notes": "All checklist items cleared. Quantity within acceptable range for chronic use."
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `verified` | boolean | Yes | `true` = verified; `false` = failed verification |
| `flag_reason` | string | If verified=false | Reason for verification failure |
| `notes` | string | No | Internal compliance notes (max 1000 chars) |

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "rx_id": "rx_01J3KP7VXYZ123",
    "audit_status": "VERIFIED",
    "verified_by": "admin_01J3KP7VEEE555",
    "verified_at": "2026-07-24T09:15:00Z",
    "notes": "All checklist items cleared."
  }
}
```

---

### 4. Flag Prescription for Investigation

```POST /api/v1/admin/prescriptions/:rx_id/flag```

**Authentication:** Bearer JWT - `admin_compliance` | `admin_super`
**Rate Limit:** 20 req/min

**Request Body:**
```json
{
  "reason": "Suspected duplicate Rx for Schedule H1 drug within 30 days",
  "severity": "HIGH"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `reason` | string | Yes | Detailed reason for flagging (max 500 chars) |
| `severity` | ENUM | Yes | `LOW`, `MEDIUM`, `HIGH` |

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "rx_id": "rx_01J3KP7VXYZ123",
    "audit_status": "FLAGGED",
    "severity": "HIGH",
    "flagged_by": "admin_01J3KP7VEEE555",
    "flagged_at": "2026-07-24T09:20:00Z",
    "escalation_sent": true
  }
}
```

---

### 5. Get Compliance Statistics

```GET /api/v1/admin/prescriptions/statistics```

**Authentication:** Bearer JWT - `admin_compliance` | `admin_super`
**Rate Limit:** 30 req/min

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `from_date` | date | 30 days ago | Start of analysis window |
| `to_date` | date | today | End of analysis window |

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "period": {
      "from": "2026-06-24",
      "to": "2026-07-24"
    },
    "compliance_rate_by_schedule": {
      "H": 98.2,
      "H1": 95.4,
      "X": 93.1
    },
    "flagged_rate_pct": 3.2,
    "top_flagged_pharmacies": [
      { "pharmacy_id": "ph_01", "name": "Sunrise Pharmacy", "flagged_count": 4 },
      { "pharmacy_id": "ph_02", "name": "Apollo Medicals", "flagged_count": 2 }
    ],
    "top_flagged_drugs": [
      { "drug_name": "Alprazolam 0.5mg", "schedule": "H1", "flag_count": 6 },
      { "drug_name": "Codeine Phosphate", "schedule": "H1", "flag_count": 3 }
    ],
    "total_audited": 312,
    "total_verified": 295,
    "total_flagged": 10,
    "overdue_audits": 2
  }
}
```

---

## Data Models

### RxAuditEntry

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID v4 | PK, NOT NULL | Audit entry identifier |
| `rx_id` | UUID | FK ? prescriptions.id, NOT NULL | Audited prescription |
| `order_id` | UUID | FK ? orders.id, NOT NULL | Associated dispensed order |
| `pharmacy_id` | UUID | FK ? pharmacies.id, NOT NULL | Dispensing pharmacy |
| `schedule` | ENUM | NOT NULL | `H`, `H1`, `X`, `NONE` - highest schedule in dispense |
| `audit_status` | ENUM | NOT NULL | `AWAITING_AUDIT`, `VERIFIED`, `FLAGGED`, `OVERDUE_AUDIT` |
| `audit_deadline` | timestamp | NOT NULL | `dispensed_at + 24h` for H1/X; `dispensed_at + 7d` for H |
| `possible_duplicate` | boolean | default false | System-detected duplicate flag |
| `verified_by` | UUID | FK ? users, nullable | Admin who verified |
| `verified_at` | timestamp | nullable | Verification timestamp |
| `flag_reason` | string | nullable | Reason if flagged |
| `flag_severity` | ENUM | nullable | `LOW`, `MEDIUM`, `HIGH` |
| `flagged_by` | UUID | FK ? users, nullable | Admin who flagged |
| `flagged_at` | timestamp | nullable | Flag timestamp |
| `notes` | string | nullable | Audit notes |
| `created_at` | timestamp | NOT NULL | Entry creation (= dispense_at) |

### ComplianceAuditLog (append-only)

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID v4 | PK, NOT NULL | Log entry identifier |
| `rx_id` | UUID | FK ? prescriptions.id, nullable | Related prescription |
| `action` | ENUM | NOT NULL | `RX_VERIFIED`, `RX_FLAGGED`, `DOCTOR_VERIFIED`, `REGISTER_EXPORTED`, `FILING_MARKED` |
| `actor_id` | UUID | FK ? users.id, NOT NULL | Admin who performed action |
| `actor_role` | string | NOT NULL | Role at time of action |
| `payload` | JSONB | nullable | Action-specific data |
| `created_at` | timestamp | NOT NULL | Immutable timestamp |

---

## Acceptance Criteria

- [ ] **Given** a Schedule H1 medicine is dispensed, **when** 24 hours pass without an admin audit, **then** the audit entry status transitions to `OVERDUE_AUDIT` and a compliance team alert is sent.
- [ ] **Given** a patient has the same Schedule H1 drug dispensed twice within 30 days, **when** the second audit detail is loaded, **then** the `possible_duplicate` flag is `true` and the duplicate entry is referenced.
- [ ] **Given** `admin_compliance` calls `POST /api/v1/admin/prescriptions/:rx_id/flag` with `severity: HIGH`, **when** successful, **then** an email escalation is sent to the Head of Compliance within 60 seconds.
- [ ] **Given** an audit action (verify or flag) is committed, **when** an admin attempts to undo or edit it, **then** the API returns 405 Method Not Allowed (append-only constraint).
- [ ] **Given** `admin_operations` calls `GET /api/v1/admin/prescriptions/:rx_id`, **when** the request is made, **then** the prescription `file_url` field is absent from the response (access restricted).
- [ ] **Given** the audit queue has 50 entries, **when** an export is requested (`?export=true`), **then** a CSV download URL is returned within 5 seconds containing all 50 entries with Rx ID, patient, drug, schedule, pharmacy, dispense date, and audit outcome.
- [ ] **Given** `GET /api/v1/admin/prescriptions/statistics` is called for a 30-day window, **when** the response is received, **then** `compliance_rate_by_schedule` contains separate rates for H, H1, and X.
- [ ] **Given** OCR has extracted doctor name and medicines for an uploaded prescription, **when** the audit detail is loaded, **then** verification checklist fields are pre-populated and labeled `"OCR extracted"`.

---

## Dependencies

| Dependency | Story / System | Notes |
|------------|---------------|-------|
| EPIC-008 STORY-001 - Prescription upload | Upstream | Provides Rx data and file_url |
| EPIC-008 STORY-002 - Pharmacy Rx queue | Upstream | Dispense event triggers audit entry creation |
| EPIC-008 STORY-004 - Drug register | Downstream | Verified/flagged audits feed the H1/X register |
| EPIC-008 STORY-005 - Doctor registry | Bidirectional | Doctor verification status shown in checklist |
| Notification service (email + push) | Platform | Escalation alerts for HIGH severity flags |
| Auth / RBAC | EPIC-001 | Role-gated endpoints |

---

## Notes

- The audit queue must be indexed by `(schedule, audit_status, audit_deadline)` for performant overdue queries.
- `OVERDUE_AUDIT` status transition is handled by a scheduled job running every 15 minutes.
- The compliance activity log (`ComplianceAuditLog`) is shared with STORY-006 (filings activity log). Both write to the same table.
