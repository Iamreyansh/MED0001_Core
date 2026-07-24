# STORY-003: Teleconsult Session Lifecycle Management

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-003 |
| **Epic** | EPIC-009 - Doctor Teleconsult |
| **Priority** | P0 |
| **Complexity** | M |
| **Status** | Draft |

---

## Overview

This story manages the operational lifecycle of an active teleconsult session - from when the doctor picks up the consult (DOCTOR_REVIEWING) through the phone call (CALLING ? IN_CALL) to completion. The session is admin-driven: doctors or the admin operations team advance the consult through status transitions via the admin endpoint. Call timestamps (`call_started_at`, `call_ended_at`) are logged for duration tracking, SLA monitoring, and doctor stats. Post-completion, patients can optionally rate the consult (1-5 stars with optional text). The story also provides the admin-facing consult queue and list views for operational oversight.

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| `admin_super` | Full access | All actions |
| `admin_operations` | Full access | Manage queue, advance status, view consults |
| `customer` | Rate only | Can submit rating post-completion |

---

## Business Rules

1. **State machine enforcement:** Consult status follows a strict sequence: `REQUESTED ? DOCTOR_REVIEWING ? CALLING ? IN_CALL ? COMPLETED`. Skipping states is not permitted (e.g., cannot go from `REQUESTED` directly to `IN_CALL`). The only non-sequential transition allowed is to `CANCELLED` from any state before `COMPLETED`.
2. **Call timestamps are system-logged:** `call_started_at` is set by the system when the status transitions to `IN_CALL`. `call_ended_at` is set when the status transitions to `COMPLETED`. These fields cannot be manually overridden via the API.
3. **Call duration computation:** Consult duration = `call_ended_at - call_started_at` in minutes. If `call_started_at` is null (e.g., patient unreachable), duration = 0 and the consult is completed as `advice_only` with a note.
4. **Completed consult always results in an e-prescription or advice note:** When status is advanced to `COMPLETED`, the system checks that either an `e_prescription_id` is linked or `is_advice_only: true` is set on the consult. If neither condition is met, the `COMPLETED` transition is blocked with `EPRESCRIPTION_REQUIRED`.
5. **Rating is optional and post-completion only:** `POST /api/v1/consults/:consult_id/rate` can only be called when the consult `status = COMPLETED`. Rating can be submitted once; a second rating attempt returns `ALREADY_RATED`.
6. **Doctor `last_assigned_at` update:** When a consult transitions to `COMPLETED`, the assigned doctor's `last_assigned_at` is updated to `call_ended_at` and `total_consults` is incremented.
7. **Audio-only in v1:** Calls are outbound phone calls - the doctor uses the internal calling service to dial `patient_phone`. No VoIP, video, or in-app audio is supported in v1. The platform records only the timestamps of the call, not the call content.
8. **Queue display order:** The admin consult queue is sorted by: (1) `IN_CALL` first, (2) `CALLING`, (3) `DOCTOR_REVIEWING`, (4) `REQUESTED` oldest-first. Within each status group, entries are sorted by `created_at ASC` (FIFO).

---

## API Endpoints

### 1. Update Consult Status (Admin/Doctor-side)

```POST /api/v1/admin/consults/:consult_id/status```

**Authentication:** Bearer JWT - `admin_super` | `admin_operations`
**Rate Limit:** 30 req/min

**Request Body:**
```json
{
  "status": "IN_CALL",
  "notes": "Patient answered. Call in progress."
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `status` | ENUM | Yes | Target status (must follow state machine sequence) |
| `notes` | string | No | Internal notes for this transition (max 500 chars) |

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "consult_id": "consult_01J3KP7VKKK111",
    "status": "IN_CALL",
    "previous_status": "CALLING",
    "call_started_at": "2026-07-24T10:33:00Z",
    "updated_at": "2026-07-24T10:33:00Z"
  }
}
```

**Errors:**

| Code | HTTP | Description |
|------|------|-------------|
| `CONSULT_NOT_FOUND` | 404 | Consult ID not found |
| `INVALID_STATUS_TRANSITION` | 422 | Requested transition violates state machine |
| `EPRESCRIPTION_REQUIRED` | 422 | Cannot complete without e-Rx or advice_only flag |

**Valid state transitions:**

| From | Allowed To |
|------|-----------|
| `REQUESTED` | `DOCTOR_REVIEWING`, `CANCELLED` |
| `DOCTOR_REVIEWING` | `CALLING`, `CANCELLED` |
| `CALLING` | `IN_CALL`, `CANCELLED` |
| `IN_CALL` | `COMPLETED`, `CANCELLED` |
| `COMPLETED` | (terminal) |
| `CANCELLED` | (terminal) |

---

### 2. Get Admin Consult Queue

```GET /api/v1/admin/consults/queue```

**Authentication:** Bearer JWT - `admin_super` | `admin_operations`
**Rate Limit:** 30 req/min

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "status_counts": {
      "REQUESTED": 2,
      "DOCTOR_REVIEWING": 1,
      "CALLING": 0,
      "IN_CALL": 3,
      "total_active": 6
    },
    "pending_list": [
      {
        "consult_id": "consult_01J3KP7VKKK111",
        "status": "IN_CALL",
        "patient_name": "Ravi Kumar",
        "doctor_name": "Dr. Anil Mehta",
        "medicines_requested": ["Metformin 500mg", "Glipizide 5mg"],
        "call_started_at": "2026-07-24T10:33:00Z",
        "wait_time_minutes": 4,
        "is_cart_mode": true
      },
      {
        "consult_id": "consult_01J3KP7VMMM333",
        "status": "REQUESTED",
        "patient_name": "Sunita Patel",
        "doctor_name": null,
        "medicines_requested": ["Atorvastatin 10mg"],
        "call_started_at": null,
        "wait_time_minutes": 8,
        "is_cart_mode": false
      }
    ]
  }
}
```

---

### 3. Rate Consult (Customer)

```POST /api/v1/consults/:consult_id/rate```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 5 req/min

**Request Body:**
```json
{
  "rating": 5,
  "feedback_text": "Doctor was very helpful and explained everything clearly."
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `rating` | integer | Yes | 1-5 star rating |
| `feedback_text` | string | No | Written feedback (max 500 chars) |

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "consult_id": "consult_01J3KP7VKKK111",
    "rating": 5,
    "feedback_text": "Doctor was very helpful and explained everything clearly.",
    "rated_at": "2026-07-24T11:15:00Z"
  }
}
```

**Errors:**

| Code | HTTP | Description |
|------|------|-------------|
| `CONSULT_NOT_COMPLETED` | 409 | Consult is not in COMPLETED status |
| `ALREADY_RATED` | 409 | Customer has already rated this consult |
| `INVALID_RATING` | 422 | Rating is not in range 1-5 |

---

### 4. List Admin Consults

```GET /api/v1/admin/consults```

**Authentication:** Bearer JWT - `admin_super` | `admin_operations`
**Rate Limit:** 30 req/min

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `date` | date | today | Filter by date (consult created_at) |
| `doctor_id` | UUID | - | Filter by assigned doctor |
| `status` | string | `ALL` | Filter by status |
| `is_cart_mode` | boolean | - | Filter cart-mode consults |
| `page` | integer | 1 | Pagination |
| `limit` | integer | 20 | Items per page |

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "stats": {
      "total_today": 52,
      "completed": 44,
      "in_progress": 6,
      "cancelled": 2,
      "avg_duration_minutes": 6.3,
      "avg_rating": 4.6,
      "pending_rating": 11
    },
    "consults": [
      {
        "consult_id": "consult_01J3KP7VKKK111",
        "patient_name": "Ravi Kumar",
        "doctor_name": "Dr. Anil Mehta",
        "status": "COMPLETED",
        "duration_minutes": 7,
        "e_prescription_issued": true,
        "is_cart_mode": true,
        "rating": 5,
        "created_at": "2026-07-24T10:30:00Z",
        "completed_at": "2026-07-24T10:40:00Z"
      }
    ]
  },
  "meta": {
    "page": 1,
    "limit": 20,
    "total": 52,
    "total_pages": 3
  }
}
```

---

## Data Models

### ConsultStatusEvent (audit trail)

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID v4 | PK, NOT NULL | Event identifier |
| `consult_id` | UUID | FK ? consults.id, NOT NULL | Parent consult |
| `from_status` | ENUM | NOT NULL | Previous status |
| `to_status` | ENUM | NOT NULL | New status |
| `actor_id` | UUID | FK ? users.id, NOT NULL | Admin who triggered transition |
| `notes` | string | nullable | Transition notes |
| `created_at` | timestamp | NOT NULL | Transition timestamp |

### Consult (additions for session management)

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `call_started_at` | timestamp | nullable | Set on IN_CALL transition |
| `call_ended_at` | timestamp | nullable | Set on COMPLETED transition |
| `duration_minutes` | decimal(5,2) | nullable, computed | `call_ended_at - call_started_at` |
| `rating` | integer | nullable, 1-5 | Customer rating |
| `feedback_text` | string | nullable, max 500 | Written feedback |
| `rated_at` | timestamp | nullable | Rating submission time |
| `is_advice_only` | boolean | default false | No medicines prescribed |
| `clinical_notes` | text | nullable | Doctor's internal clinical notes |

---

## Acceptance Criteria

- [ ] **Given** an admin advances a consult from `REQUESTED` directly to `IN_CALL`, **when** the request is submitted, **then** the API returns HTTP 422 with `INVALID_STATUS_TRANSITION`.
- [ ] **Given** an admin advances a consult to `COMPLETED` when no e-Rx has been issued and `is_advice_only` is false, **when** the request is submitted, **then** the API returns HTTP 422 with `EPRESCRIPTION_REQUIRED`.
- [ ] **Given** a consult transitions to `IN_CALL`, **when** the transition succeeds, **then** `call_started_at` is set by the system to the current UTC timestamp and cannot be modified by the API caller.
- [ ] **Given** a customer submits a rating of 5 for a completed consult, **when** the rating is saved, **then** the assigned doctor's `avg_rating` is updated using the running average formula.
- [ ] **Given** a customer attempts to rate a consult that is still `IN_CALL`, **when** the rating request is submitted, **then** the API returns HTTP 409 with `CONSULT_NOT_COMPLETED`.
- [ ] **Given** a customer rates the same consult twice, **when** the second rating is submitted, **then** the API returns HTTP 409 with `ALREADY_RATED`.
- [ ] **Given** the admin queue is loaded, **when** the response is received, **then** `IN_CALL` consults appear before `CALLING`, `CALLING` before `DOCTOR_REVIEWING`, and `DOCTOR_REVIEWING` before `REQUESTED`.
- [ ] **Given** a consult transitions to `COMPLETED`, **when** the transition succeeds, **then** the assigned doctor's `total_consults` is incremented and `last_assigned_at` is updated to `call_ended_at`.

---

## Dependencies

| Dependency | Story / System | Notes |
|------------|---------------|-------|
| EPIC-009 STORY-002 - Consult request | Upstream | Consults created here are managed in this story |
| EPIC-009 STORY-001 - Doctor profile | Bidirectional | `total_consults` and `avg_rating` updated on completion |
| EPIC-009 STORY-004 - e-Prescription generation | Upstream | e-Rx issuance is prerequisite for COMPLETED transition |
| Notification service (Push) | Platform | Status update push notifications to customer |

---

## Notes

- Call recording is not in scope for v1. Only timestamps are stored.
- `clinical_notes` on the consult are internal to Namma MedMate and are never exposed to the customer or pharmacy. They inform the e-Rx but are not included in any customer-facing response.
- The admin queue endpoint (`/admin/consults/queue`) is intended for real-time polling (every 10-15 seconds) on the admin dashboard. Consider a WebSocket or SSE endpoint for v2 to reduce polling load.
