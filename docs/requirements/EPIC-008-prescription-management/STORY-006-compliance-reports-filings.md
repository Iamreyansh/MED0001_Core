# STORY-006: Regulatory Compliance Filings and Reports

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-006 |
| **Epic** | EPIC-008 - Prescription Management |
| **Priority** | P1 |
| **Complexity** | M |
| **Status** | Draft |

---

## Overview

This story manages the regulatory filing calendar and compliance activity log for Namma MedMate. Filings are periodic deliverables - primarily the Schedule H1 and X drug registers - that must be submitted to the Drugs Control Department on a monthly basis. The system auto-generates the filing schedule, allows `admin_compliance` to generate the required reports (CSV or PDF), and marks filings as submitted with an official reference number. An adverse event reporting module allows manual entry of reportable adverse drug events. A drug recall mechanism (initiated by `admin_compliance`) triggers an immediate platform-level ban on the recalled batch. A full compliance activity log provides an auditable, append-only record of every compliance action taken on the platform.

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| `admin_compliance` | Full access | View filings, generate reports, mark filed, initiate recalls, view activity log |
| `admin_super` | Full access | Same as admin_compliance |
| `admin_operations` | Read-only | View filing calendar and activity log |
| `admin_finance` | Read-only | View filing status for audit trail |

---

## Business Rules

1. **Auto-generated monthly filing calendar:** On the first day of each calendar month, the system auto-creates filing entries for Schedule H1 Register and Schedule X Register for the preceding month. Due date is the 15th of the current month. If the 15th falls on a Sunday or national holiday, due date shifts to the next business day.
2. **Overdue filing escalation:** Filings not marked as `FILED` by their due date transition to `OVERDUE` status. An email alert is sent to `admin_compliance` and `admin_super` on the due date and again at 3 days overdue.
3. **Report generation is async:** `POST /api/v1/admin/compliance/filings/:filing_id/generate` enqueues a generation job and returns a `job_id`. The generated report is stored in S3 with a 24-hour download link. Concurrent generation requests for the same filing are deduplicated.
4. **Adverse event reports are manual entries:** Adverse drug event (ADE) filings are entered manually by `admin_compliance`. They are linked to an order and patient where applicable. ADE filings are submitted to CDSCO's PvPI (Pharmacovigilance Programme of India) portal.
5. **Drug recall initiates platform-wide ban:** When `admin_compliance` initiates a drug recall for a specific batch number, the system immediately sets `is_banned = true` on all inventory items matching the drug name + batch number across all pharmacies, prevents new orders for that item, and sends a WhatsApp alert to all affected pharmacy owners.
6. **Compliance activity log is append-only:** Every compliance action - Rx verification, doctor verification, register export, recall, filing marked - writes an immutable log entry. Logs are never edited or deleted. Retention: 7 years (mirrors longest statutory requirement).
7. **Filing reference number is mandatory for FILED status:** Marking a filing as `FILED` requires a `reference_number` (the official acknowledgement number from the regulatory authority). This prevents accidental marking without actual submission.
8. **Archive after 5 years:** Filings older than 5 years are archived (soft-delete to cold storage). They remain queryable with `is_archived: true` but do not appear in default listing.

---

## API Endpoints

### 1. List Regulatory Filings

```GET /api/v1/admin/compliance/filings```

**Authentication:** Bearer JWT - `admin_compliance` | `admin_super` | `admin_operations` | `admin_finance`
**Rate Limit:** 30 req/min

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `filing_type` | string | `ALL` | `SCHEDULE_H1_REGISTER`, `SCHEDULE_X_REGISTER`, `ADVERSE_EVENTS`, `DRUG_RECALL` |
| `status` | string | `ALL` | `PENDING`, `FILED`, `OVERDUE` |
| `year` | integer | current year | Filter by year |
| `page` | integer | 1 | Pagination |
| `limit` | integer | 20 | Items per page |

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "kpis": {
      "pending_filings": 2,
      "overdue_filings": 0
    },
    "filings": [
      {
        "filing_id": "filing_01J3KP7VXYZ123",
        "filing_type": "SCHEDULE_H1_REGISTER",
        "period_label": "June 2026",
        "period_from": "2026-06-01",
        "period_to": "2026-06-30",
        "due_date": "2026-07-15",
        "status": "PENDING",
        "filed_at": null,
        "filed_by": null,
        "reference_number": null,
        "generated_report_url": null,
        "is_archived": false
      },
      {
        "filing_id": "filing_01J3KP7VABC456",
        "filing_type": "SCHEDULE_X_REGISTER",
        "period_label": "June 2026",
        "period_from": "2026-06-01",
        "period_to": "2026-06-30",
        "due_date": "2026-07-15",
        "status": "FILED",
        "filed_at": "2026-07-12T14:30:00Z",
        "filed_by": "admin_01J3KP7VEEE555",
        "reference_number": "KSDCD/2026/H1/07/4521",
        "generated_report_url": "https://s3.../reports/schedule-x-june-2026.pdf",
        "is_archived": false
      }
    ]
  },
  "meta": {
    "page": 1,
    "limit": 20,
    "total": 2,
    "total_pages": 1
  }
}
```

---

### 2. Generate Filing Report

```POST /api/v1/admin/compliance/filings/:filing_id/generate```

**Authentication:** Bearer JWT - `admin_compliance` | `admin_super`
**Rate Limit:** 5 req/min

**Request Body:**
```json
{
  "period": "2026-06",
  "format": "CSV"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `period` | string | Yes | `YYYY-MM` format |
| `format` | ENUM | Yes | `CSV` or `PDF` |

**Response `202 Accepted`:**
```json
{
  "success": true,
  "data": {
    "job_id": "gen_01J3KP7VHHH888",
    "filing_id": "filing_01J3KP7VXYZ123",
    "status": "GENERATING",
    "format": "CSV",
    "estimated_ready_seconds": 20,
    "poll_url": "/api/v1/admin/compliance/filings/filing_01J3KP7VXYZ123/generate/gen_01J3KP7VHHH888"
  }
}
```

**`GET` poll endpoint response when ready:**
```json
{
  "success": true,
  "data": {
    "job_id": "gen_01J3KP7VHHH888",
    "status": "READY",
    "download_url": "https://s3.../compliance/schedule-h1-register-jun2026.csv?X-Amz-Expires=86400",
    "row_count": 312,
    "generated_at": "2026-07-24T10:00:00Z",
    "expires_at": "2026-07-25T10:00:00Z"
  }
}
```

---

### 3. Mark Filing as Filed

```POST /api/v1/admin/compliance/filings/:filing_id/mark-filed```

**Authentication:** Bearer JWT - `admin_compliance` | `admin_super`
**Rate Limit:** 10 req/min

**Request Body:**
```json
{
  "filed_by": "admin_01J3KP7VEEE555",
  "filed_at": "2026-07-12T14:30:00Z",
  "reference_number": "KSDCD/2026/H1/07/4521"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `filed_by` | UUID | Yes | Admin user ID who filed |
| `filed_at` | timestamp | Yes | Actual submission datetime (ISO 8601) |
| `reference_number` | string | Yes | Official acknowledgement/reference number |

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "filing_id": "filing_01J3KP7VXYZ123",
    "status": "FILED",
    "filed_by": "admin_01J3KP7VEEE555",
    "filed_at": "2026-07-12T14:30:00Z",
    "reference_number": "KSDCD/2026/H1/07/4521"
  }
}
```

**Errors:**

| Code | HTTP | Description |
|------|------|-------------|
| `FILING_NOT_FOUND` | 404 | Filing ID not found |
| `FILING_ALREADY_FILED` | 409 | Filing already marked as FILED |
| `REFERENCE_NUMBER_REQUIRED` | 422 | `reference_number` is blank |

---

### 4. Get Compliance Activity Log

```GET /api/v1/admin/compliance/activity-log```

**Authentication:** Bearer JWT - `admin_compliance` | `admin_super` | `admin_operations`
**Rate Limit:** 30 req/min

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `action` | string | `ALL` | `RX_VERIFIED`, `RX_FLAGGED`, `DOCTOR_VERIFIED`, `REGISTER_EXPORTED`, `FILING_MARKED`, `DRUG_RECALLED` |
| `actor_id` | UUID | - | Filter by admin user |
| `from_date` | date | 30 days ago | Start of log window |
| `to_date` | date | today | End of log window |
| `page` | integer | 1 | Pagination |
| `limit` | integer | 50 | Items per page (max 200) |

**Response `200 OK`:**
```json
{
  "success": true,
  "data": [
    {
      "log_id": "log_01J3KP7VIII999",
      "action": "RX_VERIFIED",
      "actor_id": "admin_01J3KP7VEEE555",
      "actor_name": "Ananya Compliance",
      "actor_role": "admin_compliance",
      "rx_id": "rx_01J3KP7VXYZ123",
      "payload": { "verified": true, "notes": "All checklist items passed" },
      "created_at": "2026-07-24T09:15:00Z"
    },
    {
      "log_id": "log_01J3KP7VJJJ000",
      "action": "DRUG_RECALLED",
      "actor_id": "admin_01J3KP7VEEE555",
      "actor_name": "Ananya Compliance",
      "actor_role": "admin_compliance",
      "rx_id": null,
      "payload": { "drug_name": "Paracetamol 500mg", "batch_no": "PCM2024Q1", "pharmacies_affected": 12 },
      "created_at": "2026-07-24T11:00:00Z"
    }
  ],
  "meta": {
    "page": 1,
    "limit": 50,
    "total": 2,
    "total_pages": 1
  }
}
```

---

## Data Models

### ComplianceFiling

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID v4 | PK, NOT NULL | Filing identifier |
| `filing_type` | ENUM | NOT NULL | `SCHEDULE_H1_REGISTER`, `SCHEDULE_X_REGISTER`, `ADVERSE_EVENTS`, `DRUG_RECALL` |
| `period_from` | date | NOT NULL | Start of filing period |
| `period_to` | date | NOT NULL | End of filing period |
| `due_date` | date | NOT NULL | Regulatory due date |
| `status` | ENUM | NOT NULL, default `PENDING` | `PENDING`, `FILED`, `OVERDUE` |
| `generated_report_s3_key` | string | nullable | S3 key for generated report |
| `generated_report_format` | ENUM | nullable | `CSV` or `PDF` |
| `generated_at` | timestamp | nullable | Report generation timestamp |
| `filed_by` | UUID | FK ? users, nullable | Admin who marked as filed |
| `filed_at` | timestamp | nullable | Actual filing timestamp |
| `reference_number` | string | nullable | Official regulatory reference number |
| `is_archived` | boolean | default false | True after 5-year archival |
| `created_at` | timestamp | NOT NULL | Auto-creation timestamp |
| `updated_at` | timestamp | NOT NULL | Last update |

### ComplianceActivityLog (append-only)

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID v4 | PK, NOT NULL | Log entry identifier |
| `action` | ENUM | NOT NULL | `RX_VERIFIED`, `RX_FLAGGED`, `DOCTOR_VERIFIED`, `DOCTOR_BLACKLISTED`, `REGISTER_EXPORTED`, `FILING_MARKED`, `FILING_GENERATED`, `DRUG_RECALLED` |
| `actor_id` | UUID | FK ? users.id, NOT NULL | Admin who performed action |
| `actor_role` | string | NOT NULL | Role at time of action |
| `rx_id` | UUID | FK ? prescriptions.id, nullable | Related prescription |
| `doctor_id` | UUID | FK ? doctors.id, nullable | Related doctor |
| `filing_id` | UUID | FK ? filings.id, nullable | Related filing |
| `payload` | JSONB | nullable | Action-specific context |
| `ip_address` | string | nullable | Requester IP (for audit) |
| `created_at` | timestamp | NOT NULL | Immutable action timestamp |

---

## Acceptance Criteria

- [ ] **Given** the first day of a new calendar month arrives, **when** the monthly job runs, **then** two filing entries (SCHEDULE_H1_REGISTER and SCHEDULE_X_REGISTER) are auto-created for the preceding month with `due_date` set to the 15th.
- [ ] **Given** a filing's `due_date` has passed and it is still `PENDING`, **when** the status-check job runs, **then** the filing transitions to `OVERDUE` and an email alert is sent to `admin_compliance`.
- [ ] **Given** `admin_compliance` calls `mark-filed` without a `reference_number`, **when** the request is submitted, **then** the API returns HTTP 422 with `REFERENCE_NUMBER_REQUIRED`.
- [ ] **Given** two concurrent requests to generate the same filing report are made, **when** the second request arrives while the first is still processing, **then** the second request returns the same `job_id` (deduplicated) rather than starting a second generation job.
- [ ] **Given** a drug recall is initiated for batch `PCM2024Q1`, **when** the recall is processed, **then** all inventory items with matching `drug_name + batch_no` across all pharmacies are set to `is_banned = true` and pharmacy owners receive WhatsApp alerts.
- [ ] **Given** an admin verifies a prescription, **when** the verification succeeds, **then** a `ComplianceActivityLog` entry with `action: RX_VERIFIED` is written and cannot be deleted or modified.
- [ ] **Given** `admin_finance` calls `GET /api/v1/admin/compliance/filings`, **when** the request is made, **then** the filing list is returned (read-only access granted).
- [ ] **Given** a filing is older than 5 years, **when** the archival job runs, **then** the filing's `is_archived` is set to `true` and it no longer appears in the default listing (accessible only with `?include_archived=true`).

---

## Dependencies

| Dependency | Story / System | Notes |
|------------|---------------|-------|
| EPIC-008 STORY-003 - Rx compliance audit | Upstream | Audit actions feed into activity log |
| EPIC-008 STORY-004 - Drug register | Upstream | Register data is the source for filing generation |
| EPIC-008 STORY-005 - Doctor registry | Upstream | Doctor verification actions logged |
| EPIC-006 - Pharmacy inventory | Downstream | Drug recall sets `is_banned` on inventory items |
| Notification service (email + WhatsApp) | Platform | Filing overdue alerts and recall notifications |
| S3 bucket (compliance reports) | Infrastructure | Long-lived signed URLs (24h) for generated reports |
| Scheduled job runner (cron) | Platform | Monthly filing creation, overdue detection, archival |

---

## Notes

- Monthly filing auto-creation cron: `0 9 1 * *` (9 AM IST on the 1st of each month).
- Overdue check cron: `0 0 * * *` (midnight IST daily).
- Archival cron: `0 2 1 1 *` (2 AM IST on 1 Jan each year) - archives filings from 5+ years ago.
- PDF format for filings is generated using a regulatory-approved template; CSV format reuses the drug register export from STORY-004.
- Adverse event filing entries are created manually via a form in Admin HQ (no API in v1 scope beyond listing); the `ADVERSE_EVENTS` type is stored and tracked in the filing calendar.
