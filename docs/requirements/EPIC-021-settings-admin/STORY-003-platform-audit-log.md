# STORY-003: Platform Audit Log

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-003 |
| **Epic** | EPIC-021 - Settings & Platform Administration |
| **Priority** | P0 |
| **Complexity** | L |
| **Status** | Draft |

---

## Overview

This story delivers a tamper-proof, append-only audit trail for every state-changing admin action on the Namma MedMate platform. A middleware layer automatically intercepts all non-GET admin API calls, captures the actor identity, the action performed, the resource affected, and JSON snapshots of the before and after states. The audit log is retained for 2 years and is queryable with rich filters (actor, resource type, date range) and exportable to CSV. Read-only operations (GET) are not logged. System-generated actions (automation engine, background jobs) are logged with `actor_type: SYSTEM`. The audit log is a critical compliance asset and must never be modified or deleted.

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| admin_super | Read | Full access to the audit log including exports |
| admin_operations | Read | Can view operational audit entries (orders, pharmacies, riders) |
| admin_finance | Read | Can view financial audit entries (settlements, refunds, payouts) |
| admin_compliance | Read | Can view compliance-related audit entries (prescriptions, KYC) |
| admin_support | Read | Can view support-relevant audit entries (tickets, customer flags) |

## Business Rules

1. Every state-changing admin action (POST, PUT, PATCH, DELETE on admin API endpoints) is automatically logged via a middleware component. The middleware executes asynchronously (fire-and-forget write) to avoid adding latency to the primary request path.
2. The `before_state` and `after_state` fields are JSON snapshots of the full resource record before and after the change. For creation operations, `before_state` is `null`. For deletion operations, `after_state` is `null`.
3. The audit log is strictly append-only. No UPDATE or DELETE operations are permitted on the `audit_logs` table at the database level (enforced via a database trigger or row-level security policy that blocks `UPDATE` and `DELETE` DML on this table).
4. Audit log entries are retained for a minimum of 2 years from `timestamp`. A scheduled archival job moves records older than 2 years to cold storage (S3/Glacier) rather than deleting them, as regulatory requirements may vary.
5. All admin actions are logged - there are no exceptions for admin_super. System-generated actions (run by the automation engine or background jobs) are logged with `actor_type: SYSTEM` and `actor_id` set to the job's identifier.
6. The audit log supports CSV export for date-range queries via the `export` flag. Large exports (> 10,000 records) are generated asynchronously and delivered via a pre-signed S3 download URL with a 1-hour expiry.
7. Sensitive field values (passwords, OTP hashes, payment tokens) are redacted from `before_state` and `after_state` snapshots. Redaction is handled by a middleware hook that strips fields matching a configured sensitive-fields list before serialisation.
8. The `metadata` field stores arbitrary key-value context about the request: HTTP method, request URL, query params, response status code, and any custom event-specific data set by the action handler.

## API Endpoints

### 1. List Audit Log Entries

```
GET /api/v1/admin/audit-log
```

**Authentication:** Bearer JWT - any admin role
**Rate Limit:** 20 req/min per user

**Query Parameters:**
| Param | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| page | integer | No | 1 | Page number |
| limit | integer | No | 20 | Results per page, max 100 |
| sort | string | No | timestamp | Sort field |
| order | string | No | desc | asc \| desc |
| actor_id | UUID | No | - | Filter by actor (admin staff ID) |
| actor_type | string | No | - | Filter: ADMIN \| SYSTEM \| AUTOMATION |
| resource_type | string | No | - | Filter by resource type (e.g., pharmacy, customer, order) |
| resource_id | UUID | No | - | Filter by specific resource ID |
| action | string | No | - | Filter by action string (e.g., pharmacy.suspend) |
| from | ISO 8601 | No | - | Start of date range |
| to | ISO 8601 | No | - | End of date range |
| export | boolean | No | false | If true, initiates async CSV export |

**Success Response - `200 OK`:**
```json
{
  "success": true,
  "data": [
    {
      "id": "al-uuid-1",
      "actor": {
        "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
        "name": "Ayesha Siddiqui",
        "role": "admin_super",
        "type": "ADMIN"
      },
      "action": "pharmacy.suspend",
      "resource_type": "pharmacy",
      "resource_id": "pharm-uuid-1",
      "before_state": {
        "id": "pharm-uuid-1",
        "name": "XYZ Medicals",
        "status": "ACTIVE"
      },
      "after_state": {
        "id": "pharm-uuid-1",
        "name": "XYZ Medicals",
        "status": "SUSPENDED",
        "suspended_reason": "Non-compliance: selling without Rx"
      },
      "metadata": {
        "method": "PATCH",
        "url": "/api/v1/admin/pharmacies/pharm-uuid-1/suspend",
        "status_code": 200
      },
      "ip_address": "106.51.0.1",
      "user_agent": "Mozilla/5.0...",
      "timestamp": "2026-07-24T01:00:00Z"
    }
  ],
  "meta": {
    "page": 1,
    "limit": 20,
    "total": 4820,
    "has_next": true
  }
}
```

**Success Response - `200 OK` (export requested):**
```json
{
  "success": true,
  "data": {
    "export_job_id": "exp-uuid-1",
    "status": "QUEUED",
    "message": "CSV export is being generated. You will receive an email with the download link."
  },
  "meta": {}
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `VALIDATION_ERROR` | Invalid date range format or invalid filter values |
| 401 | `UNAUTHORIZED` | Token missing or invalid |
| 403 | `FORBIDDEN` | Non-admin user |

---

### 2. Get Single Audit Log Entry

```
GET /api/v1/admin/audit-log/:id
```

**Authentication:** Bearer JWT - any admin role
**Rate Limit:** 30 req/min per user

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| :id | UUID | Audit log entry ID |

**Success Response - `200 OK`:**
```json
{
  "success": true,
  "data": {
    "id": "al-uuid-1",
    "actor": {
      "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
      "name": "Ayesha Siddiqui",
      "role": "admin_super",
      "type": "ADMIN"
    },
    "action": "pharmacy.suspend",
    "resource_type": "pharmacy",
    "resource_id": "pharm-uuid-1",
    "before_state": {
      "id": "pharm-uuid-1",
      "name": "XYZ Medicals",
      "status": "ACTIVE",
      "owner_name": "Rajiv Nair",
      "city": "Bengaluru"
    },
    "after_state": {
      "id": "pharm-uuid-1",
      "name": "XYZ Medicals",
      "status": "SUSPENDED",
      "suspended_reason": "Non-compliance: selling without Rx",
      "suspended_by": "3fa85f64-5717-4562-b3fc-2c963f66afa6"
    },
    "diff": [
      { "path": "/status", "op": "replace", "from": "ACTIVE", "to": "SUSPENDED" },
      { "path": "/suspended_reason", "op": "add", "value": "Non-compliance: selling without Rx" },
      { "path": "/suspended_by", "op": "add", "value": "3fa85f64-5717-4562-b3fc-2c963f66afa6" }
    ],
    "metadata": {
      "method": "PATCH",
      "url": "/api/v1/admin/pharmacies/pharm-uuid-1/suspend",
      "query_params": {},
      "status_code": 200,
      "request_id": "req-uuid-1"
    },
    "ip_address": "106.51.0.1",
    "user_agent": "Mozilla/5.0...",
    "timestamp": "2026-07-24T01:00:00Z"
  },
  "meta": {}
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 401 | `UNAUTHORIZED` | Token missing or invalid |
| 403 | `FORBIDDEN` | Non-admin user |
| 404 | `AUDIT_LOG_NOT_FOUND` | No audit entry with the given ID |

---

## Data Models

### AuditLog

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | UUID | PK, auto-gen | Primary key |
| actor_id | UUID | nullable, indexed | Admin staff ID or system job ID; nullable for anonymous system events |
| actor_name | VARCHAR(100) | NOT NULL | Snapshot of actor's name at time of action |
| actor_role | VARCHAR(30) | NOT NULL | Snapshot of actor's role at time of action |
| actor_type | VARCHAR(15) | NOT NULL, default 'ADMIN' | ADMIN \| SYSTEM \| AUTOMATION |
| action | VARCHAR(100) | NOT NULL, indexed | Dot-notation action string (e.g., `pharmacy.suspend`, `customer.flag`, `feature_flag.update`) |
| resource_type | VARCHAR(50) | NOT NULL, indexed | Entity type affected (e.g., `pharmacy`, `customer`, `order`) |
| resource_id | UUID | nullable, indexed | ID of the affected entity |
| before_state | JSONB | nullable | Full resource snapshot before the change |
| after_state | JSONB | nullable | Full resource snapshot after the change |
| metadata | JSONB | nullable | HTTP context: method, url, status_code, request_id |
| ip_address | INET | NOT NULL | Originating IP address |
| user_agent | TEXT | nullable | HTTP User-Agent header |
| timestamp | TIMESTAMPTZ | NOT NULL, default NOW(), indexed | Event timestamp (used for all time-range queries) |

## Acceptance Criteria

- [ ] Given an admin_super suspends a pharmacy, when `GET /admin/audit-log?resource_type=pharmacy&resource_id=:id` is called, then the suspension action is present with `action: "pharmacy.suspend"`, `before_state.status: "ACTIVE"`, and `after_state.status: "SUSPENDED"`.
- [ ] Given an automated background job credits a wallet, when the audit log entry is created by the middleware, then `actor_type: SYSTEM` and the `actor_name` identifies the job name.
- [ ] Given `GET /admin/audit-log?from=2026-07-01&to=2026-07-24`, then only audit entries with `timestamp` in that date range are returned.
- [ ] Given an admin directly attempts `DELETE` on any record in the `audit_logs` table via the database, then the database-level constraint blocks the operation (confirmed via unit test against the DB trigger).
- [ ] Given `GET /admin/audit-log?export=true` with a large date range (> 10,000 records), then the response returns a `QUEUED` export job status rather than inline data, and the admin receives an email with a download link within 5 minutes.
- [ ] Given a password field exists in `before_state` at middleware capture time, then the field is redacted to `"[REDACTED]"` in the stored `before_state` JSONB before the audit log entry is written.
- [ ] Given `GET /admin/audit-log/:id` for a valid entry, then the response includes a `diff` array showing the JSON Patch operations between `before_state` and `after_state`.

## Dependencies

- EPIC-001 / STORY-003 - Admin authentication provides actor identity for middleware
- EPIC-021 / STORY-001 - Admin staff management generates audit entries
- EPIC-021 / STORY-002 - Feature flag changes generate audit entries
- EPIC-021 / STORY-004 - Config changes generate audit entries
- EPIC-000 / Infrastructure - S3 for large export file storage

## Notes

- The audit middleware should be implemented as a post-response hook (using a response interceptor or async queue) to avoid blocking the HTTP response. The audit write should be best-effort - a failure to write an audit entry should not fail the originating request, but should be monitored and alerted.
- The `diff` field in the single-entry detail response is computed on-the-fly using a JSON Patch diff library; it is not stored. This keeps storage minimal while offering rich diff views in the UI.
- For the sensitive-fields redaction list, maintain it as a configuration array: `["password_hash", "otp_hash", "totp_secret", "backup_codes", "razorpay_token_id", "upi_id"]`.
- Long-term consider migrating to an append-only time-series store (ClickHouse, TimescaleDB) as audit log volume grows with platform scale.
