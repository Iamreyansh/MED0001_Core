# STORY-001: Rider Onboarding & KYC

| Field | Value |
|---|---|
| Story ID | EPIC-011/STORY-001 |
| Epic | EPIC-011 - Rider Management and Delivery |
| Title | Rider Onboarding & KYC |
| Status | Draft |
| Priority | P0 |
| Estimated Effort | 2 Sprints |
| Last Updated | 2026-07-24 |

---

## Overview

This story covers the complete registration and KYC (Know Your Customer) onboarding flow for delivery riders on Namma MedMate. A new rider self-registers via the Rider App, uploads all required KYC documents (driving licence, vehicle RC, insurance, PUC certificate, Aadhaar, PAN), and submits the KYC bundle for admin review. The rider account remains in `PENDING_KYC` status and cannot receive order assignments until an admin approves the KYC. Admins can approve, reject (with reason), block, or unblock riders from the Admin HQ operations panel. The system optionally supports auto-KYC via Aadhaar OTP-based verification if the integration is enabled by configuration.

---

## User Roles

| Role | Capability |
|---|---|
| `rider` (unauthenticated) | Register account, upload KYC documents, submit KYC |
| `rider` (authenticated) | View own KYC status |
| `admin_operations` | View KYC queue, approve, reject, block, unblock riders |
| `admin_super` | All admin_operations capabilities |

---

## Business Rules

| # | Rule |
|---|---|
| BR-001 | A rider account starts in `PENDING_KYC` status after registration; they cannot go ONLINE or receive orders until status is `ACTIVE`. |
| BR-002 | A valid driving licence document is **mandatory**; KYC submission is blocked without it. |
| BR-003 | Vehicle insurance certificate and PUC certificate must not be expired at time of submission; system validates expiry date and rejects if expired. |
| BR-004 | A maximum of **5 document upload slots** exist per rider per document_type; re-uploads overwrite the previous version of the same document_type. |
| BR-005 | `vehicle_plate_number` must match the Indian RTO format regex: `^[A-Z]{2}[0-9]{1,2}[A-Z]{1,3}[0-9]{4}$` (e.g., `KA01AB1234` or `MH12DE1234`). Dashes optional in input but stripped on storage. |
| BR-006 | Insurance expiry and PUC expiry dates are stored; a scheduled job alerts the rider and admin_operations **30 days** before expiry via push notification and in-dashboard warning. |
| BR-007 | If auto-KYC via Aadhaar OTP is enabled (`feature_flag: aadhaar_kyc_enabled = true`), a successful Aadhaar OTP verification automatically sets `aadhaar_verified = true` and may fast-track KYC approval; admin still sees the record and can override. |
| BR-008 | Rejected KYC requires the rider to re-upload the flagged documents and re-submit; rejection reason is visible to the rider. |
| BR-009 | A blocked rider (`status = BLOCKED`) cannot log in to the Rider App; unblocking restores `ACTIVE` status. |
| BR-010 | Rider phone number is unique across the platform; duplicate phone returns `PHONE_ALREADY_REGISTERED` error. |

---

## API Endpoints

### POST /api/v1/rider/register

**Auth:** None (public endpoint)  
**Description:** Register a new rider account.

**Request Body:**
```json
{
  "name": "Ravi Kumar",
  "phone": "9876543210",
  "email": "ravi.kumar@example.com",
  "vehicle_type": "BIKE",
  "vehicle_plate_number": "KA01AB1234",
  "preferred_zone_id": "zone_uuid_here"
}
```

**Response 201 Created:**
```json
{
  "success": true,
  "data": {
    "rider_id": "rider_uuid",
    "name": "Ravi Kumar",
    "phone": "9876543210",
    "status": "PENDING_KYC",
    "kyc_status": "NOT_SUBMITTED",
    "created_at": "2026-07-24T01:00:00Z"
  },
  "meta": {}
}
```

**Errors:**
| Code | HTTP | Meaning |
|---|---|---|
| `PHONE_ALREADY_REGISTERED` | 409 | Phone number in use by another rider |
| `INVALID_VEHICLE_PLATE` | 422 | Plate number does not match Indian RTO format |
| `INVALID_ZONE` | 422 | `preferred_zone_id` does not exist |
| `VALIDATION_ERROR` | 400 | Missing or malformed required fields |

---

### POST /api/v1/rider/kyc/documents

**Auth:** `Bearer JWT` (rider)  
**Description:** Upload a single KYC document. Multipart form data.

**Request:** `Content-Type: multipart/form-data`
```
document_type: DRIVING_LICENCE
file: <binary>
expiry_date: "2029-12-31"   (required for VEHICLE_INSURANCE, PUC_CERTIFICATE)
document_number: "KA-2010-0012345"  (optional; stored for reference)
```

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "document_id": "doc_uuid",
    "rider_id": "rider_uuid",
    "document_type": "DRIVING_LICENCE",
    "file_url": "https://s3.amazonaws.com/medmate-kyc/rider_uuid/driving_licence.pdf",
    "expiry_date": null,
    "verification_status": "PENDING",
    "uploaded_at": "2026-07-24T01:05:00Z"
  },
  "meta": {}
}
```

**Errors:**
| Code | HTTP | Meaning |
|---|---|---|
| `INVALID_DOCUMENT_TYPE` | 422 | document_type not in allowed enum |
| `DOCUMENT_EXPIRED` | 422 | Provided expiry_date is in the past |
| `FILE_TOO_LARGE` | 413 | File exceeds 10 MB limit |
| `UNSUPPORTED_FILE_FORMAT` | 415 | Only JPEG, PNG, PDF accepted |
| `UPLOAD_LIMIT_REACHED` | 429 | Already 5 uploads for this document_type |

---

### POST /api/v1/rider/kyc/submit

**Auth:** `Bearer JWT` (rider)  
**Description:** Submit uploaded KYC documents for admin review. Validates all mandatory documents are present.

**Request Body:** `{}` (empty; server validates from stored documents)

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "rider_id": "rider_uuid",
    "kyc_status": "SUBMITTED",
    "submitted_at": "2026-07-24T01:10:00Z",
    "documents_submitted": [
      "DRIVING_LICENCE",
      "VEHICLE_RC",
      "VEHICLE_INSURANCE",
      "PUC_CERTIFICATE",
      "AADHAAR",
      "PAN"
    ],
    "review_expected_by": "2026-07-25T01:10:00Z"
  },
  "meta": {}
}
```

**Errors:**
| Code | HTTP | Meaning |
|---|---|---|
| `DRIVING_LICENCE_MISSING` | 422 | Mandatory driving licence not uploaded |
| `DOCUMENT_EXPIRED_ON_SUBMIT` | 422 | Insurance or PUC expired at submit time |
| `KYC_ALREADY_SUBMITTED` | 409 | KYC already in SUBMITTED or APPROVED state |

---

### GET /api/v1/admin/riders

**Auth:** `Bearer JWT` (admin_operations, admin_super)  
**Description:** Admin KYC queue and rider list. Supports filtering by status.

**Query Params:** `?status=PENDING_KYC&page=1&limit=20&sort=created_at&order=asc`

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "riders": [
      {
        "rider_id": "rider_uuid",
        "name": "Ravi Kumar",
        "phone": "9876543210",
        "email": "ravi.kumar@example.com",
        "vehicle_type": "BIKE",
        "vehicle_plate_number": "KA01AB1234",
        "preferred_zone_id": "zone_uuid",
        "status": "PENDING_KYC",
        "kyc_status": "SUBMITTED",
        "submitted_at": "2026-07-24T01:10:00Z",
        "created_at": "2026-07-24T01:00:00Z"
      }
    ]
  },
  "meta": {
    "page": 1,
    "limit": 20,
    "total": 45
  }
}
```

**Errors:**
| Code | HTTP | Meaning |
|---|---|---|
| `FORBIDDEN` | 403 | Insufficient role |
| `INVALID_STATUS_FILTER` | 422 | status value not in allowed enum |

---

### POST /api/v1/admin/riders/:id/approve

**Auth:** `Bearer JWT` (admin_operations, admin_super)  
**Description:** Approve a rider's KYC and activate the account.

**Request Body:**
```json
{
  "notes": "All documents verified, vehicle RC matches plate number."
}
```

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "rider_id": "rider_uuid",
    "status": "ACTIVE",
    "kyc_status": "APPROVED",
    "approved_by": "admin_uuid",
    "approved_at": "2026-07-24T09:00:00Z"
  },
  "meta": {}
}
```

**Errors:**
| Code | HTTP | Meaning |
|---|---|---|
| `RIDER_NOT_FOUND` | 404 | rider_id does not exist |
| `INVALID_KYC_STATE` | 409 | KYC not in SUBMITTED state |
| `FORBIDDEN` | 403 | Insufficient role |

---

### POST /api/v1/admin/riders/:id/reject

**Auth:** `Bearer JWT` (admin_operations, admin_super)  
**Description:** Reject rider KYC with a mandatory reason.

**Request Body:**
```json
{
  "reason": "DOCUMENT_UNCLEAR",
  "notes": "Driving licence image is blurry. Please re-upload a clear photo."
}
```

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "rider_id": "rider_uuid",
    "kyc_status": "REJECTED",
    "rejection_reason": "DOCUMENT_UNCLEAR",
    "rejection_notes": "Driving licence image is blurry. Please re-upload a clear photo.",
    "rejected_by": "admin_uuid",
    "rejected_at": "2026-07-24T09:15:00Z"
  },
  "meta": {}
}
```

**Errors:**
| Code | HTTP | Meaning |
|---|---|---|
| `RIDER_NOT_FOUND` | 404 | rider_id does not exist |
| `REASON_REQUIRED` | 422 | reason field is missing |
| `INVALID_KYC_STATE` | 409 | KYC not in SUBMITTED state |

---

### POST /api/v1/admin/riders/:id/block

**Auth:** `Bearer JWT` (admin_operations, admin_super)  
**Description:** Block an active rider (suspends access to Rider App and order assignments).

**Request Body:**
```json
{
  "reason": "FRAUD_SUSPECTED",
  "notes": "Rider reported for COD fraud. Pending investigation."
}
```

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "rider_id": "rider_uuid",
    "status": "BLOCKED",
    "blocked_by": "admin_uuid",
    "blocked_at": "2026-07-24T10:00:00Z",
    "reason": "FRAUD_SUSPECTED"
  },
  "meta": {}
}
```

**Errors:**
| Code | HTTP | Meaning |
|---|---|---|
| `RIDER_NOT_FOUND` | 404 | rider_id does not exist |
| `RIDER_ALREADY_BLOCKED` | 409 | Rider already in BLOCKED state |
| `REASON_REQUIRED` | 422 | reason is missing |

---

### POST /api/v1/admin/riders/:id/unblock

**Auth:** `Bearer JWT` (admin_operations, admin_super)  
**Description:** Unblock a rider, restoring ACTIVE status.

**Request Body:**
```json
{
  "notes": "Investigation cleared. No fraud found."
}
```

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "rider_id": "rider_uuid",
    "status": "ACTIVE",
    "unblocked_by": "admin_uuid",
    "unblocked_at": "2026-07-24T11:00:00Z"
  },
  "meta": {}
}
```

**Errors:**
| Code | HTTP | Meaning |
|---|---|---|
| `RIDER_NOT_FOUND` | 404 | rider_id does not exist |
| `RIDER_NOT_BLOCKED` | 409 | Rider is not in BLOCKED state |

---

## Data Models

### RiderProfile

| Field | Type | Nullable | Description |
|---|---|---|---|
| `id` | UUID v4 | No | Primary key |
| `name` | VARCHAR(100) | No | Full name |
| `phone` | VARCHAR(15) | No | Unique mobile number |
| `email` | VARCHAR(255) | Yes | Email address |
| `vehicle_type` | ENUM(`BIKE`,`BICYCLE`,`SCOOTER`) | No | Vehicle category |
| `vehicle_plate_number` | VARCHAR(15) | No | Normalised Indian plate format |
| `primary_zone_id` | UUID | Yes | FK ? DeliveryZone; preferred zone |
| `status` | ENUM(`PENDING_KYC`,`ACTIVE`,`OFFLINE`,`BLOCKED`) | No | Account status |
| `kyc_status` | ENUM(`NOT_SUBMITTED`,`SUBMITTED`,`APPROVED`,`REJECTED`) | No | KYC pipeline state |
| `kyc_submitted_at` | TIMESTAMPTZ | Yes | When KYC was submitted |
| `kyc_reviewed_at` | TIMESTAMPTZ | Yes | When KYC was approved/rejected |
| `kyc_reviewed_by` | UUID | Yes | FK ? AdminUser |
| `kyc_rejection_reason` | VARCHAR(100) | Yes | Standardised rejection code |
| `kyc_rejection_notes` | TEXT | Yes | Human-readable rejection notes |
| `aadhaar_verified` | BOOLEAN | No | Auto-KYC via Aadhaar OTP |
| `avg_rating` | DECIMAL(3,2) | Yes | Average customer rating (1.00-5.00) |
| `total_trips` | INTEGER | No | Lifetime completed deliveries |
| `on_time_pct` | DECIMAL(5,2) | Yes | % deliveries within SLA |
| `earnings_wallet_balance` | DECIMAL(12,2) | No | Pending earnings not yet paid out |
| `cod_in_hand` | DECIMAL(12,2) | No | Cash held by rider (not yet deposited) |
| `daily_streak_days` | INTEGER | No | Consecutive active days for incentives |
| `blocked_reason` | VARCHAR(100) | Yes | Reason for BLOCKED status |
| `blocked_by` | UUID | Yes | FK ? AdminUser |
| `blocked_at` | TIMESTAMPTZ | Yes | Timestamp of block action |
| `created_at` | TIMESTAMPTZ | No | Account creation timestamp |
| `updated_at` | TIMESTAMPTZ | No | Last update timestamp |

---

### RiderKYCDocument

| Field | Type | Nullable | Description |
|---|---|---|---|
| `id` | UUID v4 | No | Primary key |
| `rider_id` | UUID | No | FK ? RiderProfile |
| `document_type` | ENUM(`DRIVING_LICENCE`,`VEHICLE_RC`,`VEHICLE_INSURANCE`,`PUC_CERTIFICATE`,`AADHAAR`,`PAN`) | No | Document category |
| `document_number` | VARCHAR(50) | Yes | Document ID/number (masked in UI) |
| `file_url` | TEXT | No | S3 private URL |
| `file_size_bytes` | INTEGER | No | File size for quota checks |
| `mime_type` | VARCHAR(50) | No | e.g., `application/pdf`, `image/jpeg` |
| `expiry_date` | DATE | Yes | For insurance, PUC, driving licence |
| `expiry_alert_sent` | BOOLEAN | No | 30-day alert dispatched |
| `verification_status` | ENUM(`PENDING`,`APPROVED`,`REJECTED`) | No | Document-level verification |
| `rejection_reason` | VARCHAR(255) | Yes | If document-level rejection |
| `uploaded_at` | TIMESTAMPTZ | No | Upload timestamp |
| `reviewed_at` | TIMESTAMPTZ | Yes | Review timestamp |
| `reviewed_by` | UUID | Yes | FK ? AdminUser |

---

## Acceptance Criteria

| # | Criterion |
|---|---|
| AC-001 | A rider can successfully register with valid inputs; account is created with `status = PENDING_KYC` and `kyc_status = NOT_SUBMITTED`. |
| AC-002 | Registration with a duplicate phone number returns HTTP 409 with error code `PHONE_ALREADY_REGISTERED`. |
| AC-003 | Registration with an invalid vehicle plate (e.g., `KA-123`) returns HTTP 422 with error code `INVALID_VEHICLE_PLATE`. |
| AC-004 | Attempting KYC submission without uploading a driving licence returns HTTP 422 with `DRIVING_LICENCE_MISSING`. |
| AC-005 | Uploading a document with an expired insurance date returns HTTP 422 with `DOCUMENT_EXPIRED`. |
| AC-006 | Admin approving a KYC in `SUBMITTED` state sets `status = ACTIVE` and `kyc_status = APPROVED`; rider receives push notification. |
| AC-007 | Admin rejecting a KYC sets `kyc_status = REJECTED` with the provided reason; rider can view the reason and re-submit after correcting documents. |
| AC-008 | Blocking an ACTIVE rider sets `status = BLOCKED`; blocked rider receives HTTP 401 on Rider App login attempts. |
| AC-009 | Unblocking a BLOCKED rider restores `status = ACTIVE`. |
| AC-010 | A scheduled job sends an expiry alert notification 30 days before insurance or PUC expiry date; `expiry_alert_sent` is set to `true` after dispatch to prevent duplicate alerts. |

---

## Dependencies

| Dependency | Type | Notes |
|---|---|---|
| AWS S3 | External | KYC document storage; private ACL; pre-signed URLs for admin review |
| Auth Service (EPIC-001) | Internal | JWT issuance on registration; role = `rider` |
| Notification Service (EPIC-013) | Internal | Push notifications for approval, rejection, expiry alerts |
| Feature Flag Service | Internal | `aadhaar_kyc_enabled` flag gates Aadhaar OTP integration |
| Aadhaar OTP API (DigiLocker / Sandbox) | External (optional) | Auto-KYC verification; disabled by default |
| Scheduled Job Runner | Internal | Daily cron to check expiry dates and dispatch 30-day alerts |

---

## Notes

- KYC document files are stored on S3 with private ACL; the admin UI fetches pre-signed URLs valid for 15 minutes.
- The `vehicle_plate_number` is normalised to uppercase with dashes stripped before storage; display formatting (with dashes) is applied in the UI layer.
- In phase 1 all KYC review is manual; the Aadhaar OTP auto-KYC path is behind a feature flag and does not skip the admin approval step in v1.
- KYC rejection does **not** delete uploaded documents; the rider sees them as "REJECTED" and can upload replacements.
- Rider registration does not require an OTP in v1 (phone OTP verification is planned for v2 to reduce drop-off).
