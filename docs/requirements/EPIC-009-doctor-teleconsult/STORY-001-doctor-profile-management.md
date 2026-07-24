# STORY-001: Teleconsult Doctor Profile Management

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-001 |
| **Epic** | EPIC-009 - Doctor Teleconsult |
| **Priority** | P1 |
| **Complexity** | M |
| **Status** | Draft |

---

## Overview

This story covers the management of Namma MedMate's internal teleconsult doctor roster - the qualified physicians who conduct free patient consultations and issue e-prescriptions. Doctors in this roster are employees or contractors of Namma MedMate (distinct from the prescribing doctors in the EPIC-008 doctor registry, who are external prescribers on patient-uploaded prescriptions). Only `admin_super` can add or modify doctor profiles. Availability is toggled manually (no shift scheduling in v1), and doctor assignment to incoming consults uses a load-balancing algorithm (least-recently-assigned available doctor). The story also provides a stats endpoint for admin oversight of each doctor's consultation load and patient satisfaction.

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| `admin_super` | Full access | Create, update, delete, toggle availability |
| `admin_compliance` | Read-only | View roster for credential verification |
| `admin_operations` | Read-only | View roster and stats for operational oversight |
| `customer` | None | Cannot access doctor management |
| `pharmacy_owner` | None | Cannot access doctor management |

---

## Business Rules

1. **admin_super only for mutations:** Only `admin_super` can create, update, or toggle a doctor's profile. All other admin roles have read-only access. Attempting a mutation with a non-`admin_super` role returns HTTP 403.
2. **Valid medical registration required:** A doctor cannot be added without a valid `registration_no` (Medical Council of India or State Board). The registration number is validated for format (state code + numeric) but not against live NMC API in v1. `registration_no` must be unique in the teleconsult doctor table.
3. **Availability toggle:** `is_available` is set manually via `PATCH /api/v1/admin/teleconsult/doctors/:id/availability`. Availability is the only field on this sub-endpoint; changing other profile fields requires the main PATCH endpoint.
4. **Load-balancing assignment:** When a consult is requested with `slot: NOW`, the system selects the available doctor (`is_available = true`) with the oldest `last_assigned_at` timestamp (least recently used). If no doctors are available, the patient is added to a queue and shown an estimated wait time.
5. **Rating is a computed average:** `avg_rating` on the doctor record is updated after each consult rating submission using a running average formula. It is not recomputed from scratch on each read.
6. **Teleconsult doctors are separate from OCR-extracted prescribing doctors:** The `TeleconsultDoctor` table is separate from the `Doctor` table in EPIC-008. A teleconsult doctor's e-prescriptions appear in the EPIC-008 doctor registry with `source: TELECONSULT` and `status: VERIFIED`.
7. **Doctor bio and avatar are required for patient-facing display:** The patient consult request UI shows the assigned doctor's name, qualification, avatar, and short bio. These fields are required before a doctor can be set to `is_available: true`.

---

## API Endpoints

### 1. List Teleconsult Doctors

```GET /api/v1/admin/teleconsult/doctors```

**Authentication:** Bearer JWT - `admin_super` | `admin_compliance` | `admin_operations`
**Rate Limit:** 30 req/min

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `is_available` | boolean | - | Filter by availability |
| `specialty` | string | - | Filter by specialty |
| `page` | integer | 1 | Pagination |
| `limit` | integer | 20 | Items per page |

**Response `200 OK`:**
```json
{
  "success": true,
  "data": [
    {
      "id": "tdoc_01J3KP7VXYZ123",
      "name": "Dr. Anil Mehta",
      "qualification": "MBBS MD",
      "registration_no": "DL98765",
      "specialty": "General Medicine",
      "rating": 4.7,
      "years_experience": 12,
      "languages": ["Hindi", "English", "Kannada"],
      "is_available": true,
      "consults_today": 8,
      "total_consults": 1247,
      "last_assigned_at": "2026-07-24T00:45:00Z",
      "avatar_url": "https://cdn.nammamedmate.com/doctors/anil-mehta.jpg",
      "created_at": "2025-01-15T09:00:00Z"
    }
  ],
  "meta": {
    "page": 1,
    "limit": 20,
    "total": 1,
    "total_pages": 1
  }
}
```

---

### 2. Add Teleconsult Doctor

```POST /api/v1/admin/teleconsult/doctors```

**Authentication:** Bearer JWT - `admin_super`
**Rate Limit:** 10 req/min

**Request Body:**
```json
{
  "name": "Dr. Kavitha Reddy",
  "qualification": "MBBS MS",
  "registration_no": "AP54321",
  "specialty": "General Medicine",
  "languages_spoken": ["Telugu", "English", "Hindi"],
  "years_experience": 8,
  "avatar_url": "https://cdn.nammamedmate.com/doctors/kavitha-reddy.jpg",
  "bio": "Dr. Kavitha Reddy is a General Medicine specialist with 8 years of experience in primary care and preventive medicine.",
  "internal_phone": "+91-9123456780"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `name` | string | Yes | Doctor's full name with prefix |
| `qualification` | ENUM | Yes | `MBBS`, `MBBS MD`, `MBBS MS`, `BDS`, `BAMS`, `BHMS` |
| `registration_no` | string | Yes | Medical council registration number (unique) |
| `specialty` | string | Yes | Primary specialty (max 100 chars) |
| `languages_spoken` | string[] | Yes | Array of language names (min 1) |
| `years_experience` | integer | Yes | Years of clinical experience (> 0) |
| `avatar_url` | string | Yes | CDN URL for profile photo |
| `bio` | string | Yes | Short bio for patient display (max 500 chars) |
| `internal_phone` | string | Yes | Doctor's phone for outbound calls (never exposed to patients) |

**Response `201 Created`:**
```json
{
  "success": true,
  "data": {
    "id": "tdoc_01J3KP7VABC789",
    "name": "Dr. Kavitha Reddy",
    "qualification": "MBBS MS",
    "registration_no": "AP54321",
    "specialty": "General Medicine",
    "languages_spoken": ["Telugu", "English", "Hindi"],
    "years_experience": 8,
    "avatar_url": "https://cdn.nammamedmate.com/doctors/kavitha-reddy.jpg",
    "is_available": false,
    "avg_rating": null,
    "total_consults": 0,
    "created_at": "2026-07-24T10:00:00Z"
  }
}
```

**Errors:**

| Code | HTTP | Description |
|------|------|-------------|
| `REGISTRATION_NO_DUPLICATE` | 409 | Doctor with this registration_no already exists |
| `INVALID_QUALIFICATION` | 422 | Qualification not in allowed ENUM |

---

### 3. Update Doctor Profile

```PATCH /api/v1/admin/teleconsult/doctors/:id```

**Authentication:** Bearer JWT - `admin_super`
**Rate Limit:** 10 req/min

**Request Body:** Any subset of mutable fields (partial update):
```json
{
  "bio": "Updated bio text.",
  "years_experience": 13,
  "languages_spoken": ["Hindi", "English", "Kannada", "Marathi"]
}
```

**Response `200 OK`:** Returns updated doctor object (same schema as GET).

---

### 4. Toggle Doctor Availability

```PATCH /api/v1/admin/teleconsult/doctors/:id/availability```

**Authentication:** Bearer JWT - `admin_super`
**Rate Limit:** 30 req/min

**Request Body:**
```json
{
  "is_available": true
}
```

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "id": "tdoc_01J3KP7VXYZ123",
    "is_available": true,
    "updated_at": "2026-07-24T10:15:00Z"
  }
}
```

**Errors:**

| Code | HTTP | Description |
|------|------|-------------|
| `DOCTOR_PROFILE_INCOMPLETE` | 422 | Cannot set available=true without avatar_url and bio |

---

### 5. Get Doctor Consult Stats

```GET /api/v1/admin/teleconsult/doctors/:id/stats```

**Authentication:** Bearer JWT - `admin_super` | `admin_operations`
**Rate Limit:** 30 req/min

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `period` | string | `7d` | `today`, `7d`, `30d`, `90d` |

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "doctor_id": "tdoc_01J3KP7VXYZ123",
    "period": "7d",
    "consults_today": 8,
    "consults_period": 52,
    "avg_call_duration_minutes": 6.3,
    "avg_rating": 4.7,
    "e_prescriptions_issued": 48,
    "advice_only_consults": 4,
    "patient_satisfaction_rate": 94.2,
    "consults_by_day": [
      { "date": "2026-07-18", "count": 7 },
      { "date": "2026-07-19", "count": 9 }
    ]
  }
}
```

---

## Data Models

### TeleconsultDoctor

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID v4 | PK, NOT NULL | Doctor identifier |
| `name` | string | NOT NULL | Full name with prefix (Dr.) |
| `qualification` | ENUM | NOT NULL | `MBBS`, `MBBS MD`, `MBBS MS`, `BDS`, `BAMS`, `BHMS`, `BUMS` |
| `registration_no` | string | UNIQUE, NOT NULL | Medical council number |
| `specialty` | string | NOT NULL | Primary specialty |
| `languages_spoken` | string[] | NOT NULL | Languages for patient-facing display |
| `years_experience` | integer | NOT NULL, > 0 | Years of practice |
| `avatar_url` | string | NOT NULL | CDN URL for profile photo |
| `bio` | text | NOT NULL | Short bio (max 500 chars) |
| `internal_phone` | string | NOT NULL | Outbound call number (never exposed to API consumers) |
| `is_available` | boolean | NOT NULL, default false | Current availability flag |
| `avg_rating` | decimal(3,2) | nullable | Running average rating (1.00-5.00) |
| `total_consults` | integer | default 0 | All-time consult count |
| `consults_today` | integer | computed/cached | Today's consult count (reset at midnight IST) |
| `last_assigned_at` | timestamp | nullable | For load-balancing: time of last consult assignment |
| `created_at` | timestamp | NOT NULL | Profile creation |
| `updated_at` | timestamp | NOT NULL | Last update |

---

## Acceptance Criteria

- [ ] **Given** `admin_super` creates a doctor profile with all required fields, **when** the request succeeds, **then** the doctor is created with `is_available: false` and `total_consults: 0`.
- [ ] **Given** `admin_super` attempts to add a doctor with a `registration_no` already in the system, **when** the request is submitted, **then** the API returns HTTP 409 with `REGISTRATION_NO_DUPLICATE`.
- [ ] **Given** `admin_super` tries to set `is_available: true` for a doctor with no `avatar_url`, **when** the request is submitted, **then** the API returns HTTP 422 with `DOCTOR_PROFILE_INCOMPLETE`.
- [ ] **Given** `admin_operations` attempts to call `POST /api/v1/admin/teleconsult/doctors`, **when** the request is made, **then** the API returns HTTP 403 Forbidden.
- [ ] **Given** two doctors are available and both have been assigned consults, **when** a new consult is assigned, **then** the doctor with the older `last_assigned_at` is selected (least-recently-used load balancing).
- [ ] **Given** a consult is rated by a patient with a score of 5, **when** the doctor previously had `avg_rating: 4.6` from 50 consults, **then** the new `avg_rating` is updated to the running average of 51 consults.
- [ ] **Given** the `GET /api/v1/admin/teleconsult/doctors/:id/stats` is called with `period: today`, **when** the response is returned, **then** `consults_today` matches the count of completed consults for that doctor since midnight IST.
- [ ] **Given** `admin_super` updates a doctor's `languages_spoken`, **when** the update succeeds, **then** the updated list is reflected immediately in the doctor detail and in patient-facing consult assignment responses.

---

## Dependencies

| Dependency | Story / System | Notes |
|------------|---------------|-------|
| EPIC-009 STORY-002 - Consult scheduling | Downstream | Doctor load-balancing uses `is_available` and `last_assigned_at` |
| EPIC-009 STORY-003 - Session management | Downstream | Stats derived from completed consult records |
| EPIC-008 STORY-005 - Doctor registry | Downstream | Teleconsult doctors appear in the prescribing doctor registry as VERIFIED |
| Auth / RBAC | EPIC-001 | `admin_super` role enforcement |

---

## Notes

- `internal_phone` is stored encrypted at rest and is never returned in any API response outside of the internal doctor-calling service. The patient never receives the doctor's phone number.
- `consults_today` can be a cached counter (Redis) reset at midnight IST via a scheduled job, rather than a live DB count, to avoid expensive real-time aggregation.
- Doctor profile photos must be uploaded to the CDN before the doctor is added; the add endpoint accepts a CDN URL, not a direct file upload.
