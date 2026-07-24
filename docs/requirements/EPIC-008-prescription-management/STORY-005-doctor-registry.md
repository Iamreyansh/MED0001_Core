# STORY-005: Prescribing Doctor Registry and Verification

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-005 |
| **Epic** | EPIC-008 - Prescription Management |
| **Priority** | P1 |
| **Complexity** | M |
| **Status** | Draft |

---

## Overview

This story manages the registry of prescribing doctors whose prescriptions are processed through Namma MedMate. Doctor profiles are auto-created from two sources: OCR extraction of uploaded prescriptions (extracting doctor name, registration number, qualifications) and verified data from the teleconsult e-prescription service. The registry enables `admin_compliance` to verify doctors against the Medical Council of India (NMC) or State Medical Council registries and to blacklist fraudulent or invalid prescribers. A blacklisted doctor's prescriptions are automatically flagged on any future submission, and an unverified doctor count is surfaced in the admin KYC queue to prioritise verification work.

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| `admin_compliance` | Full access | View directory, verify, blacklist, view unverified queue |
| `admin_super` | Full access | Same as admin_compliance |
| `admin_operations` | Read-only | View directory for informational purposes |
| `admin_support` | Read-only | View doctor profile during dispute resolution |
| `pharmacy_owner` | None | Doctors visible in Rx detail but not as a separate registry |

---

## Business Rules

1. **Auto-creation from two sources:** Doctor profiles are upserted (not duplicated) when (a) an uploaded prescription is processed by OCR and a doctor name + registration number is extracted, or (b) a teleconsult e-prescription is issued by an internal Namma MedMate doctor. OCR-sourced profiles start with `status: UNVERIFIED`; teleconsult doctors are created as `VERIFIED` (admin-managed).
2. **Registration number uniqueness:** `registration_no` is the unique key for a doctor record. If OCR extracts a registration number that already exists in the registry, the existing record's `prescription_count` is incremented; no duplicate is created. If `registration_no` is absent from OCR (illegible Rx), the doctor is created with a synthetic ID and `registration_no: UNKNOWN-{uuid_prefix}`.
3. **Blacklist propagation:** When a doctor is blacklisted, all future prescriptions submitted with that doctor's `registration_no` are automatically flagged with `BLACKLISTED_DOCTOR` severity `HIGH` in the compliance audit queue. Existing unflagged prescriptions from the same doctor are batch-flagged asynchronously.
4. **NMC registry check (v1 manual, v2 automated):** In v1, verification is performed manually by the admin - the admin sets `verification_method` to `STATE_BOARD` or `MANUAL` and marks the doctor verified. In v2, an automated NMC API lookup will be integrated. The `verification_method` field accommodates both flows.
5. **Unverified count in KYC queue:** The count of doctors with `status: UNVERIFIED` is surfaced on the Admin HQ dashboard KYC widget. The `GET /api/v1/admin/doctors/unverified` endpoint returns the paginated unverified list sorted by `prescription_count DESC` (highest-priority first).
6. **Qualification validation:** Allowed qualification codes are `MBBS`, `MBBS MD`, `MBBS MS`, `BDS`, `BAMS`, `BHMS`, `BUMS`, `MDS`, `MD` (standalone for post-grad specialists). Prescriptions from practitioners with unlisted qualifications are auto-flagged.
7. **Scheduled drug count:** `scheduled_drug_count` on the doctor record tracks how many Schedule H1/X prescriptions have been associated with this doctor. A disproportionately high count triggers a soft alert to `admin_compliance`.
8. **Blacklist reason is permanent record:** Once a doctor is blacklisted, the `blacklist_reason` and `blacklisted_at` are immutable. A blacklisted doctor can only be un-blacklisted by `admin_super` with an explicit un-blacklist action (not in v1 scope; blacklisted is terminal in v1).

---

## API Endpoints

### 1. List Doctor Directory

```GET /api/v1/admin/doctors```

**Authentication:** Bearer JWT - `admin_compliance` | `admin_super` | `admin_operations` | `admin_support`
**Rate Limit:** 60 req/min

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `search` | string | - | Partial match on name or registration_no |
| `specialty` | string | - | Filter by specialty |
| `status` | string | `ALL` | `UNVERIFIED`, `VERIFIED`, `BLACKLISTED`, `ALL` |
| `page` | integer | 1 | Pagination |
| `limit` | integer | 20 | Items per page (max 100) |
| `sort` | string | `prescription_count` | `name`, `prescription_count`, `scheduled_drug_count`, `verified_at` |
| `order` | string | `desc` | `asc` or `desc` |

**Response `200 OK`:**
```json
{
  "success": true,
  "data": [
    {
      "id": "doc_01J3KP7VXYZ123",
      "registration_no": "MH12345",
      "name": "Dr. Priya Sharma",
      "qualification": "MBBS MD",
      "specialty": "Endocrinology",
      "prescription_count": 47,
      "scheduled_drug_count": 12,
      "status": "VERIFIED",
      "verification_method": "STATE_BOARD",
      "verified_at": "2026-06-15T10:00:00Z",
      "source": "OCR"
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

### 2. Get Doctor Detail

```GET /api/v1/admin/doctors/:id```

**Authentication:** Bearer JWT - `admin_compliance` | `admin_super` | `admin_operations` | `admin_support`
**Rate Limit:** 60 req/min

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "id": "doc_01J3KP7VXYZ123",
    "registration_no": "MH12345",
    "name": "Dr. Priya Sharma",
    "qualification": "MBBS MD",
    "specialty": "Endocrinology",
    "status": "VERIFIED",
    "verification_method": "STATE_BOARD",
    "verified_at": "2026-06-15T10:00:00Z",
    "verified_by": "admin_01J3KP7VEEE555",
    "source": "OCR",
    "prescription_stats": {
      "total_prescriptions": 47,
      "scheduled_h_count": 5,
      "scheduled_h1_count": 10,
      "scheduled_x_count": 2,
      "prescriptions_by_category": {
        "Antibiotics": 15,
        "Antidiabetics": 20,
        "Anxiolytics": 12
      }
    },
    "associated_orders_count": 42,
    "blacklisted": false,
    "blacklist_reason": null,
    "blacklisted_at": null,
    "created_at": "2026-03-01T08:00:00Z",
    "updated_at": "2026-06-15T10:00:00Z"
  }
}
```

---

### 3. Verify Doctor Registration

```POST /api/v1/admin/doctors/:id/verify```

**Authentication:** Bearer JWT - `admin_compliance` | `admin_super`
**Rate Limit:** 20 req/min

**Request Body:**
```json
{
  "verified": true,
  "verification_method": "STATE_BOARD",
  "notes": "Verified against Maharashtra Medical Council registry. Reg. active."
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `verified` | boolean | Yes | `true` = verified; `false` = rejected/unverifiable |
| `verification_method` | ENUM | Yes | `NMC_REGISTRY`, `STATE_BOARD`, `MANUAL` |
| `notes` | string | No | Verification notes (max 500 chars) |

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "id": "doc_01J3KP7VXYZ123",
    "status": "VERIFIED",
    "verification_method": "STATE_BOARD",
    "verified_by": "admin_01J3KP7VEEE555",
    "verified_at": "2026-07-24T09:00:00Z"
  }
}
```

---

### 4. Blacklist a Doctor

```POST /api/v1/admin/doctors/:id/blacklist```

**Authentication:** Bearer JWT - `admin_compliance` | `admin_super`
**Rate Limit:** 10 req/min

**Request Body:**
```json
{
  "reason": "Registration number MH12345 reported as fraudulent by Maharashtra Medical Council. All prescriptions invalid."
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `reason` | string | Yes | Detailed blacklist reason (max 1000 chars) |

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "id": "doc_01J3KP7VXYZ123",
    "status": "BLACKLISTED",
    "blacklist_reason": "Registration number MH12345 reported as fraudulent...",
    "blacklisted_by": "admin_01J3KP7VEEE555",
    "blacklisted_at": "2026-07-24T09:30:00Z",
    "retroactive_flags_queued": 47,
    "message": "Doctor blacklisted. 47 associated prescriptions queued for retroactive flagging."
  }
}
```

**Errors:**

| Code | HTTP | Description |
|------|------|-------------|
| `DOCTOR_NOT_FOUND` | 404 | Doctor ID not found |
| `DOCTOR_ALREADY_BLACKLISTED` | 409 | Doctor already has BLACKLISTED status |

---

### 5. List Unverified Doctors

```GET /api/v1/admin/doctors/unverified```

**Authentication:** Bearer JWT - `admin_compliance` | `admin_super`
**Rate Limit:** 30 req/min

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `page` | integer | 1 | Pagination |
| `limit` | integer | 20 | Items per page |
| `sort` | string | `prescription_count` | Sort priority |

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "total_unverified": 23,
    "doctors": [
      {
        "id": "doc_01J3KP7VABC789",
        "registration_no": "KA99999",
        "name": "Dr. Suresh Nair",
        "qualification": "MBBS",
        "specialty": null,
        "prescription_count": 18,
        "scheduled_drug_count": 6,
        "source": "OCR",
        "first_seen_at": "2026-05-10T12:00:00Z"
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

## Data Models

### Doctor

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID v4 | PK, NOT NULL | Doctor identifier |
| `registration_no` | string | UNIQUE, NOT NULL | Medical council registration number |
| `name` | string | NOT NULL | Doctor's full name (from OCR or e-Rx) |
| `qualification` | ENUM | nullable | `MBBS`, `MBBS MD`, `MBBS MS`, `BDS`, `BAMS`, `BHMS`, `BUMS`, `MDS`, `MD` |
| `specialty` | string | nullable | Medical specialty (free text, from OCR) |
| `status` | ENUM | NOT NULL, default `UNVERIFIED` | `UNVERIFIED`, `VERIFIED`, `BLACKLISTED` |
| `source` | ENUM | NOT NULL | `OCR`, `TELECONSULT`, `MANUAL` |
| `prescription_count` | integer | default 0 | Total Rx processed through platform |
| `scheduled_drug_count` | integer | default 0 | Count of H1/X prescriptions |
| `verification_method` | ENUM | nullable | `NMC_REGISTRY`, `STATE_BOARD`, `MANUAL` |
| `verified_by` | UUID | FK ? users, nullable | Admin who verified |
| `verified_at` | timestamp | nullable | Verification timestamp |
| `blacklist_reason` | text | nullable | Reason if blacklisted |
| `blacklisted_by` | UUID | FK ? users, nullable | Admin who blacklisted |
| `blacklisted_at` | timestamp | nullable | Blacklist timestamp |
| `created_at` | timestamp | NOT NULL | First seen on platform |
| `updated_at` | timestamp | NOT NULL | Last update |

---

## Acceptance Criteria

- [ ] **Given** an uploaded prescription is processed by OCR with an extractable registration number, **when** the OCR job completes, **then** a doctor record is created (or incremented) with `status: UNVERIFIED` and the `prescription_count` is incremented.
- [ ] **Given** the same `registration_no` appears on two different uploaded prescriptions, **when** both are processed, **then** only one doctor record exists and `prescription_count = 2`.
- [ ] **Given** a doctor is blacklisted, **when** a new prescription is uploaded with that doctor's `registration_no`, **then** the compliance audit entry is auto-flagged with `BLACKLISTED_DOCTOR` severity `HIGH` before any pharmacist review.
- [ ] **Given** a doctor is blacklisted, **when** the blacklist endpoint is called, **then** the response includes `retroactive_flags_queued` count and the async job begins processing existing unflagged Rx entries.
- [ ] **Given** `admin_compliance` calls `POST /api/v1/admin/doctors/:id/blacklist` on an already-blacklisted doctor, **then** the API returns HTTP 409 with `DOCTOR_ALREADY_BLACKLISTED`.
- [ ] **Given** `GET /api/v1/admin/doctors/unverified` is called, **when** the response is received, **then** doctors are sorted by `prescription_count DESC` so highest-volume unverified doctors are prioritised.
- [ ] **Given** an OCR job extracts a qualification not in the allowed ENUM list, **when** the doctor profile is created, **then** the `qualification` field is set to `null` and the associated prescription's compliance entry is auto-flagged with a `UNRECOGNISED_QUALIFICATION` note.
- [ ] **Given** `admin_operations` attempts to call `POST /api/v1/admin/doctors/:id/verify`, **then** the API returns HTTP 403 Forbidden.

---

## Dependencies

| Dependency | Story / System | Notes |
|------------|---------------|-------|
| EPIC-008 STORY-001 - Prescription upload + OCR | Upstream | OCR extraction creates doctor profiles |
| EPIC-009 STORY-001 - Teleconsult doctor profile | Upstream | Teleconsult doctors added as VERIFIED |
| EPIC-008 STORY-003 - Compliance audit | Downstream | Blacklist propagation creates audit flags |
| Notification service | Platform | Alert to compliance team on blacklist action |
| Auth / RBAC | EPIC-001 | Role-gated endpoints |

---

## Notes

- The NMC public registry at `https://www.nmc.org.in/information-desk/doctor-registration-detail/` can be queried via web scraping in v1 as a manual verification aid (copy-paste flow). Automated API integration is flagged for v2.
- `registration_no: UNKNOWN-{uuid_prefix}` entries should be reviewed manually when the OCR confidence score for the registration field is below 85%.
- `scheduled_drug_count` alert threshold: if a doctor has `scheduled_drug_count > 50` within any 30-day window, a soft alert is raised for `admin_compliance` review.
