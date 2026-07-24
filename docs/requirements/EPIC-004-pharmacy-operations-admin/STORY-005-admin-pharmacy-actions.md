# STORY-004-005: Admin Pharmacy Actions

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-004-005 |
| **Epic** | EPIC-004 - Pharmacy Operations (Admin View) |
| **Priority** | P1 |
| **Complexity** | M |
| **Status** | Draft |

---

## Overview

This story covers structured communication, internal note-taking, call logging, and bulk operational actions that admin teams perform on pharmacies. Admin operations and support staff send notices (WhatsApp, email, in-app) to pharmacies, maintain internal admin notes visible only within the admin HQ, log support calls, and execute bulk actions (suspend, notice, export) across multiple pharmacies simultaneously. These capabilities enable efficient account management at scale and maintain a complete interaction history per pharmacy for accountability and context during escalations. Bulk actions are async, returning a job ID for status polling.

---

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| `admin_super` | Full | All actions including bulk suspend, add notes, send notices |
| `admin_operations` | Full | Send notices, add notes, log calls, bulk notices |
| `admin_support` | Write (notes, calls, notices) | Add notes, log calls, send NORMAL priority notices |
| `admin_finance` | Read notes | Read internal notes and call logs only |
| `admin_compliance` | Write notes | Add compliance-specific notes; send notices via EMAIL only |

---

## Business Rules

1. **Notice rate limiting per pharmacy**: Maximum 3 notices of any channel combination can be sent to a single pharmacy within any rolling 1-hour window. A 4th attempt within the hour returns `NOTICE_RATE_LIMIT_EXCEEDED` with seconds until the window resets.
2. **WhatsApp notices use pre-approved templates**: WhatsApp messages can only be sent using Meta-approved template names. Free-form WhatsApp messages are not permitted. The `message` field in the notice request is used as the template variable fill (not as raw message body). Template selection is based on `alert_type` or `priority`.
3. **Internal notes are admin-only**: Notes added via POST `/notes` are never exposed to pharmacy owners or pharmacy staff. They appear only in the admin HQ notice/notes feed for the pharmacy.
4. **Flagged notes are highlighted**: Notes with `is_flagged=true` appear with a visual alert indicator in the admin pharmacy detail view. Flagged notes are used to mark urgent compliance or fraud concerns.
5. **Call logs are immutable after creation**: Once a call log entry is created, it cannot be edited or deleted. This ensures an accurate audit trail of all admin-pharmacy phone interactions.
6. **Bulk suspend requires `admin_super` role**: The `SUSPEND` action in the bulk endpoint is restricted to `admin_super`. Other bulk actions (`SEND_NOTICE`, `EXPORT`) can be performed by `admin_operations` or above.
7. **Bulk actions are asynchronous**: POST `/bulk-action` returns immediately with a `job_id`. The action is executed as a background job. The job status can be polled via a separate endpoint. Max 100 pharmacy IDs per bulk action request.
8. **Bulk notice respects the 3/hour rate limit per pharmacy**: If a pharmacy in the bulk set has already received 3 notices in the current hour, that pharmacy is skipped in the bulk notice action and listed in the job result as `skipped_pharmacies`.
9. **All notices are logged in audit trail**: Every notice sent (individual or bulk) creates an `AuditLog` entry with `action=NOTICE_SENT`, channel, pharmacy_id, actor_id, and message summary.
10. **URGENT priority notices trigger immediate escalation**: Notices with `priority=URGENT` are sent via WhatsApp AND email simultaneously. NORMAL priority notices use in-app + WhatsApp only.

---

## API Endpoints

### 1. Send Notice to Pharmacy

```
POST /api/v1/admin/pharmacies/:id/notice
```

**Authentication:** Bearer JWT - `admin_super`, `admin_operations`, `admin_support`, `admin_compliance`
**Rate Limit:** 30 req/min per admin

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| `id` | UUID | Pharmacy ID |

**Request Body (application/json):**
```json
{
  "channel": "string - required, enum: WHATSAPP | EMAIL | IN_APP | ALL",
  "subject": "string - required for EMAIL and IN_APP, max 200 chars",
  "message": "string - required, max 2000 chars; for WhatsApp, used as template variable fill",
  "priority": "string - optional, enum: NORMAL | URGENT; default NORMAL",
  "template_name": "string - required when channel includes WHATSAPP; pre-approved Meta template name"
}
```

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "notice_id": "uuid-v4",
    "pharmacy_id": "uuid-v4",
    "channels_sent": ["WHATSAPP", "IN_APP"],
    "priority": "NORMAL",
    "sent_at": "2026-07-24T00:00:00Z",
    "rate_limit_remaining": 2,
    "rate_limit_reset_at": "2026-07-24T01:00:00Z"
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `INVALID_CHANNEL` | `channel` not in allowed enum |
| 400 | `SUBJECT_REQUIRED` | EMAIL or IN_APP channel without subject |
| 400 | `TEMPLATE_REQUIRED` | WhatsApp channel without `template_name` |
| 400 | `INVALID_TEMPLATE` | `template_name` not in the approved template registry |
| 403 | `FORBIDDEN` | Caller not authorised (e.g., admin_compliance sending WHATSAPP) |
| 404 | `PHARMACY_NOT_FOUND` | Pharmacy ID not found |
| 429 | `NOTICE_RATE_LIMIT_EXCEEDED` | 3 notices already sent this hour |

---

### 2. Add Internal Admin Note

```
POST /api/v1/admin/pharmacies/:id/notes
```

**Authentication:** Bearer JWT - `admin_super`, `admin_operations`, `admin_support`, `admin_compliance`, `admin_finance`
**Rate Limit:** 60 req/min

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| `id` | UUID | Pharmacy ID |

**Request Body (application/json):**
```json
{
  "note": "string - required, max 2000 chars, plain text or markdown",
  "is_flagged": "boolean - optional, default false; flagged notes are visually highlighted in admin UI"
}
```

**Success Response - 201 Created:**
```json
{
  "success": true,
  "data": {
    "note_id": "uuid-v4",
    "pharmacy_id": "uuid-v4",
    "note": "Pharmacy owner called to confirm FSSAI renewal is in progress.",
    "is_flagged": false,
    "added_by": {
      "admin_id": "uuid-v4",
      "name": "Ananya Krishnan",
      "role": "admin_operations"
    },
    "created_at": "2026-07-24T00:00:00Z"
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `NOTE_REQUIRED` | `note` is empty |
| 403 | `FORBIDDEN` | Caller not an admin role |
| 404 | `PHARMACY_NOT_FOUND` | Pharmacy ID not found |

---

### 3. List Internal Admin Notes

```
GET /api/v1/admin/pharmacies/:id/notes
```

**Authentication:** Bearer JWT - `admin_super`, `admin_operations`, `admin_support`, `admin_compliance`, `admin_finance`
**Rate Limit:** 60 req/min

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| `id` | UUID | Pharmacy ID |

**Query Parameters:**
| Param | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `is_flagged` | boolean | No | - | Filter to flagged notes only |
| `page` | integer | No | 1 | Page number |
| `limit` | integer | No | 20 | Records per page, max 100 |

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "pharmacy_id": "uuid-v4",
    "notes": [
      {
        "note_id": "uuid-v4",
        "note": "Drug Licence renewal reminder sent via WhatsApp. Owner confirmed renewal in 2 weeks.",
        "is_flagged": false,
        "added_by": {
          "admin_id": "uuid-v4",
          "name": "Ananya Krishnan",
          "role": "admin_operations"
        },
        "created_at": "2026-07-24T00:00:00Z"
      },
      {
        "note_id": "uuid-v4",
        "note": "COMPLIANCE CONCERN: Three customer complaints about expired medicines. Escalated to compliance team.",
        "is_flagged": true,
        "added_by": {
          "admin_id": "uuid-v4",
          "name": "Vikram Nair",
          "role": "admin_compliance"
        },
        "created_at": "2026-07-20T12:00:00Z"
      }
    ]
  },
  "meta": {
    "page": 1,
    "limit": 20,
    "total": 8,
    "total_pages": 1
  }
}
```

---

### 4. Log a Phone Call to Pharmacy

```
POST /api/v1/admin/pharmacies/:id/call-log
```

**Authentication:** Bearer JWT - `admin_super`, `admin_operations`, `admin_support`
**Rate Limit:** 30 req/min

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| `id` | UUID | Pharmacy ID |

**Request Body (application/json):**
```json
{
  "duration_seconds": "integer - required, min 1; call duration in seconds",
  "call_outcome": "string - required, enum: RESOLVED | FOLLOW_UP_REQUIRED | NO_ANSWER | CALLBACK_SCHEDULED | ESCALATED",
  "notes": "string - optional, max 1000 chars, summary of call discussion"
}
```

**Success Response - 201 Created:**
```json
{
  "success": true,
  "data": {
    "call_log_id": "uuid-v4",
    "pharmacy_id": "uuid-v4",
    "duration_seconds": 342,
    "duration_formatted": "5m 42s",
    "call_outcome": "FOLLOW_UP_REQUIRED",
    "notes": "Discussed fill rate issues. Owner cited staff shortage this week. Follow up in 3 days.",
    "logged_by": {
      "admin_id": "uuid-v4",
      "name": "Ananya Krishnan",
      "role": "admin_operations"
    },
    "logged_at": "2026-07-24T00:00:00Z"
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `INVALID_CALL_OUTCOME` | `call_outcome` not in allowed enum |
| 400 | `DURATION_REQUIRED` | `duration_seconds` is missing or < 1 |
| 404 | `PHARMACY_NOT_FOUND` | Pharmacy ID not found |

---

### 5. Bulk Action on Multiple Pharmacies

```
POST /api/v1/admin/pharmacies/bulk-action
```

**Authentication:** Bearer JWT - `admin_super`, `admin_operations`
**Rate Limit:** 5 req/min per admin

**Request Body (application/json):**
```json
{
  "pharmacy_ids": ["uuid-v4", "uuid-v4"],
  "action": "string - required, enum: SUSPEND | SEND_NOTICE | EXPORT",
  "payload": {
    "reason": "string - required for SUSPEND action, max 500 chars",
    "suspend_type": "string - required for SUSPEND: TEMPORARY | PERMANENT",
    "channel": "string - required for SEND_NOTICE: WHATSAPP | EMAIL | IN_APP",
    "subject": "string - required for SEND_NOTICE + EMAIL/IN_APP",
    "message": "string - required for SEND_NOTICE",
    "template_name": "string - required for SEND_NOTICE + WHATSAPP",
    "priority": "string - optional for SEND_NOTICE: NORMAL | URGENT"
  }
}
```

**Success Response - 202 Accepted:**
```json
{
  "success": true,
  "data": {
    "job_id": "uuid-v4",
    "action": "SEND_NOTICE",
    "total_pharmacies": 45,
    "status": "QUEUED",
    "estimated_completion_seconds": 30,
    "poll_url": "/api/v1/admin/bulk-jobs/uuid-v4"
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `PHARMACY_IDS_REQUIRED` | `pharmacy_ids` is empty or missing |
| 400 | `TOO_MANY_PHARMACIES` | More than 100 pharmacy IDs in request |
| 400 | `INVALID_ACTION` | `action` not in allowed enum |
| 400 | `PAYLOAD_INCOMPLETE` | Required payload fields for the action are missing |
| 403 | `FORBIDDEN_SUSPEND` | SUSPEND action called by non-admin_super role |

---

### 6. Get Bulk Action Job Status

```
GET /api/v1/admin/bulk-jobs/:job_id
```

**Authentication:** Bearer JWT - `admin_super`, `admin_operations`
**Rate Limit:** 30 req/min

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| `job_id` | UUID | Bulk action job ID |

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "job_id": "uuid-v4",
    "action": "SEND_NOTICE",
    "status": "COMPLETED",
    "total_pharmacies": 45,
    "processed": 45,
    "succeeded": 43,
    "failed": 0,
    "skipped": 2,
    "skipped_pharmacies": [
      { "pharmacy_id": "uuid-v4", "reason": "NOTICE_RATE_LIMIT_EXCEEDED" },
      { "pharmacy_id": "uuid-v4", "reason": "PHARMACY_NOT_ACTIVE" }
    ],
    "started_at": "2026-07-24T00:00:00Z",
    "completed_at": "2026-07-24T00:00:28Z"
  },
  "meta": {}
}
```

---

## Data Models

### PharmacyNotice

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID v4 | PK | Notice record ID |
| `pharmacy_id` | UUID | FK ? Pharmacy.id, not null | Target pharmacy |
| `channel` | TEXT[] | Not null | Channels used: WHATSAPP, EMAIL, IN_APP |
| `subject` | VARCHAR(200) | Nullable | Notice subject |
| `message` | TEXT | Not null | Notice body |
| `template_name` | VARCHAR(100) | Nullable | WhatsApp template name |
| `priority` | ENUM | Not null, default NORMAL | NORMAL \| URGENT |
| `sent_by` | UUID | FK ? User.id, not null | Admin who sent |
| `sent_at` | TIMESTAMPTZ | Not null | Dispatch timestamp |
| `bulk_job_id` | UUID | FK ? BulkActionJob.id, nullable | Associated bulk job if sent in bulk |

### AdminNote

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID v4 | PK | Note record ID |
| `pharmacy_id` | UUID | FK ? Pharmacy.id, not null | Target pharmacy |
| `note` | TEXT | Not null | Note content (plain text or markdown) |
| `is_flagged` | BOOLEAN | Not null, default false | Flag for urgent/compliance notes |
| `added_by` | UUID | FK ? User.id, not null | Admin who added the note |
| `created_at` | TIMESTAMPTZ | Not null, default now() | Note creation timestamp (immutable) |

### PharmacyCallLog

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID v4 | PK | Call log record ID |
| `pharmacy_id` | UUID | FK ? Pharmacy.id, not null | Pharmacy called |
| `duration_seconds` | INTEGER | Not null, min 1 | Call duration |
| `call_outcome` | ENUM | Not null | RESOLVED \| FOLLOW_UP_REQUIRED \| NO_ANSWER \| CALLBACK_SCHEDULED \| ESCALATED |
| `notes` | TEXT | Nullable | Call discussion summary |
| `logged_by` | UUID | FK ? User.id, not null | Admin who logged |
| `logged_at` | TIMESTAMPTZ | Not null, default now() | Log creation timestamp (immutable) |

### BulkActionJob

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID v4 | PK | Job ID |
| `action` | ENUM | Not null | SUSPEND \| SEND_NOTICE \| EXPORT |
| `payload` | JSONB | Not null | Action-specific payload |
| `pharmacy_ids` | UUID[] | Not null | Target pharmacy IDs |
| `status` | ENUM | Not null, default QUEUED | QUEUED \| RUNNING \| COMPLETED \| FAILED |
| `total_pharmacies` | INTEGER | Not null | Total pharmacies in batch |
| `processed` | INTEGER | Not null, default 0 | Processed count |
| `succeeded` | INTEGER | Not null, default 0 | Success count |
| `failed` | INTEGER | Not null, default 0 | Failure count |
| `skipped` | INTEGER | Not null, default 0 | Skipped count |
| `skipped_pharmacies` | JSONB | Nullable | Array of `{ pharmacy_id, reason }` |
| `initiated_by` | UUID | FK ? User.id, not null | Admin who initiated |
| `started_at` | TIMESTAMPTZ | Nullable | Job start time |
| `completed_at` | TIMESTAMPTZ | Nullable | Job completion time |
| `created_at` | TIMESTAMPTZ | Not null, default now() | Job creation time |

---

## Acceptance Criteria

- [ ] **Given** POST `/api/v1/admin/pharmacies/:id/notice` with `channel=WHATSAPP` and a valid `template_name`, **then** the WhatsApp message is dispatched, a `PharmacyNotice` record is created, an `AuditLog` entry with `action=NOTICE_SENT` is written, and `rate_limit_remaining` is decremented.
- [ ] **Given** 3 notices have been sent to a pharmacy in the last hour, **when** a 4th is attempted, **then** HTTP 429 `NOTICE_RATE_LIMIT_EXCEEDED` is returned with `rate_limit_reset_at`.
- [ ] **Given** POST `/api/v1/admin/pharmacies/:id/notes` with `is_flagged=true`, **then** the note is stored with `is_flagged=true` and is visible to all admin roles via GET `/notes`, but is NOT exposed in any pharmacy dashboard or customer-facing API.
- [ ] **Given** POST `/api/v1/admin/pharmacies/:id/call-log` with all required fields, **then** a `PharmacyCallLog` record is created as immutable (no edit/delete endpoints exist for call logs).
- [ ] **Given** POST `/api/v1/admin/pharmacies/bulk-action` with `action=SUSPEND` called by an `admin_operations` user, **then** HTTP 403 `FORBIDDEN_SUSPEND` is returned.
- [ ] **Given** POST `/api/v1/admin/pharmacies/bulk-action` with 101 pharmacy IDs, **then** HTTP 400 `TOO_MANY_PHARMACIES` is returned.
- [ ] **Given** POST `/api/v1/admin/pharmacies/bulk-action` with `action=SEND_NOTICE` on 45 pharmacies where 2 have hit rate limits, **then** HTTP 202 is returned with `job_id`, and when the job completes, GET `/bulk-jobs/:job_id` shows `succeeded=43`, `skipped=2` with reasons.
- [ ] **Given** a WhatsApp notice is sent with `template_name` not in the approved registry, **then** HTTP 400 `INVALID_TEMPLATE` is returned and no message is dispatched.

---

## Dependencies

- STORY-004-001 - Pharmacy Directory (entry point for selecting pharmacies to action)
- STORY-003-004 - KYC Status Management (suspension via bulk action)
- EPIC-002 - Notifications (WhatsApp, email, in-app delivery)
- Infrastructure: Bull/Redis - background job queue for bulk actions
- Infrastructure: WhatsApp Template Registry - pre-approved template names and variables

---

## Notes

- Approved WhatsApp template names (examples): `PHARMACY_GENERAL_NOTICE`, `PHARMACY_URGENT_ALERT`, `PHARMACY_COMPLIANCE_WARNING`, `PHARMACY_PERFORMANCE_NOTICE`. All templates must have the variable slots pre-defined with Meta.
- The note content field supports markdown. The admin UI renders it as rich text. API consumers should treat it as a markdown string.
- Bulk EXPORT action generates a CSV of the selected pharmacy_ids' profile data. It uses the same format as the directory export endpoint (STORY-004-001) but scoped to the specified IDs. The export result is available as a download link in the job result.
- The rate-limit key format in Redis: `pharmacy_notice_rate:{pharmacy_id}:{current_hour_epoch}`. TTL is set to 3600 seconds on first notice in the hour.
- Bulk action jobs should be retried up to 3 times per pharmacy on transient failures (network errors, DB timeouts). Permanent errors (pharmacy not found, rate limit) are counted as skipped, not failed.
