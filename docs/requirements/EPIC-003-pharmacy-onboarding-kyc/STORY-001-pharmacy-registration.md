# STORY-003-001: Pharmacy Registration

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-003-001 |
| **Epic** | EPIC-003 - Pharmacy Onboarding & KYC |
| **Priority** | P0 |
| **Complexity** | M |
| **Status** | Draft |

---

## Overview

This story covers the initial self-service registration flow for a pharmacy owner to create an account on Namma MedMate. The pharmacy owner submits business details and compliance identifiers, verifies their email via OTP, and receives a PENDING_KYC account. No marketplace access is granted until KYC is fully approved. The registration form is the first touchpoint and must validate all inputs strictly to minimise downstream KYC rejection. Upon completion, the system creates a `Pharmacy` record, a `User` record with the `pharmacy_owner` role, and initialises the plan as FREE.

---

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| `pharmacy_owner` (unverified) | Create | Can submit registration form and verify email; no other access until KYC approved |
| `admin_super` | Read | Can view all registrations including PENDING_KYC |
| `admin_operations` | Read | Can view and action PENDING_KYC registrations |
| Public (unauthenticated) | Create | Can submit the registration form (no auth required) |

---

## Business Rules

1. **GSTIN format validation**: GSTIN must be exactly 15 alphanumeric characters; the 2-digit state code (digits 1-2) must match one of India's 37 valid state/UT codes; the 10-character PAN embedded (digits 3-12) must match a valid PAN pattern (`[A-Z]{5}[0-9]{4}[A-Z]`); the 13th character must be a digit (entity number); the 14th character must be `Z`; the 15th is a checksum digit validated using GSTIN Luhn-style modulus-36 algorithm. Reject if any check fails with error `INVALID_GSTIN`.
2. **Drug Licence is mandatory**: `drug_licence_number` is required for all `business_type` values. Registration cannot be submitted without it.
3. **Indian phone number**: `phone` must match `+91[6-9][0-9]{9}` (10 digits starting with 6-9, prefixed with +91). Duplicate phone numbers across any user account return error `PHONE_ALREADY_REGISTERED`.
4. **Email uniqueness**: `email` must be unique across all user accounts platform-wide. Duplicate email returns `EMAIL_ALREADY_REGISTERED`. Email is case-insensitively normalised before storing.
5. **Registration creates accounts with PENDING_KYC status**: Successful registration creates a `Pharmacy` with `status=PENDING_KYC` and `plan=FREE`. The owner `User` is created with `role=pharmacy_owner` and linked via `pharmacy_id`. No marketplace features are accessible until `status=ACTIVE`.
6. **Email OTP verification is required**: The `/register` endpoint issues a 6-digit OTP to the provided email (valid 15 minutes, max 3 attempts). The pharmacy account is not usable until `/register/verify-email` succeeds. Resend allowed after 60 seconds, max 5 resends per session.
7. **Pincode must be a valid Indian 6-digit pincode**: Validated against a pincode reference table; must belong to a serviceable state. Invalid pincode returns `INVALID_PINCODE`.
8. **PAN number format validation**: `pan_number` must match `[A-Z]{5}[0-9]{4}[A-Z]`. Fourth character must correspond to entity type (P=individual/proprietor, C=company, F=firm, etc.).
9. **No duplicate Drug Licence per state**: The same `drug_licence_number` cannot be registered twice within the same state code. Returns `DRUG_LICENCE_ALREADY_REGISTERED`.
10. **Registration is idempotent on failure**: If a registration submission fails validation, no partial records are persisted. The entire registration is atomic.

---

## API Endpoints

### 1. Create Pharmacy Registration

```
POST /api/v1/pharmacy/register
```

**Authentication:** None (public endpoint)
**Rate Limit:** 5 req/min per IP; 1 registration per phone number per 24 hours

**Request Body (application/json):**
```json
{
  "owner_name": "string - required, 2-100 chars, full legal name of proprietor/director",
  "business_name": "string - required, 2-120 chars, registered business/pharmacy name",
  "phone": "string - required, format +91XXXXXXXXXX",
  "email": "string - required, valid email, max 255 chars",
  "password": "string - required, min 8 chars, must contain uppercase + digit + special char",
  "business_type": "string - required, enum: PHARMACY | HOSPITAL | CLINIC_PHARMACY",
  "address": {
    "flat": "string - required, door/flat/shop number, max 100 chars",
    "area": "string - required, locality/area name, max 200 chars",
    "city": "string - required, max 100 chars",
    "state": "string - required, max 100 chars, must be valid Indian state/UT name",
    "pincode": "string - required, exactly 6 digits",
    "latitude": "number - optional, WGS84 decimal degrees, -90 to 90",
    "longitude": "number - optional, WGS84 decimal degrees, -180 to 180"
  },
  "gstin": "string - required, exactly 15 alphanumeric chars, GSTIN format validated",
  "drug_licence_number": "string - required, max 50 chars, state drug authority format",
  "fssai_number": "string - optional, 14-digit FSSAI licence number",
  "pan_number": "string - required, 10 chars, PAN format [A-Z]{5}[0-9]{4}[A-Z]"
}
```

**Success Response - 201 Created:**
```json
{
  "success": true,
  "data": {
    "pharmacy_id": "uuid-v4",
    "status": "PENDING_KYC",
    "plan": "FREE",
    "email_verification_required": true,
    "message": "Registration submitted. Please verify your email to continue."
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `INVALID_GSTIN` | GSTIN fails format or checksum validation |
| 400 | `INVALID_PAN` | PAN number fails format validation |
| 400 | `INVALID_PINCODE` | Pincode is not a valid 6-digit Indian pincode |
| 400 | `INVALID_PHONE` | Phone not in +91XXXXXXXXXX format |
| 400 | `INVALID_BUSINESS_TYPE` | business_type not in allowed enum |
| 400 | `INVALID_STATE` | address.state is not a valid Indian state/UT |
| 400 | `INVALID_FSSAI` | Optional fssai_number present but not 14 digits |
| 400 | `INVALID_COORDINATES` | latitude/longitude out of range |
| 400 | `MISSING_REQUIRED_FIELD` | Any required field absent |
| 400 | `INVALID_PASSWORD_STRENGTH` | Password does not meet complexity requirements |
| 409 | `EMAIL_ALREADY_REGISTERED` | Email exists on any platform account |
| 409 | `PHONE_ALREADY_REGISTERED` | Phone exists on any platform account |
| 409 | `DRUG_LICENCE_ALREADY_REGISTERED` | Drug licence + state combination already in use |
| 409 | `GSTIN_ALREADY_REGISTERED` | GSTIN already registered |
| 409 | `PAN_ALREADY_REGISTERED` | PAN already registered |
| 429 | `RATE_LIMIT_EXCEEDED` | Too many registration attempts |

---

### 2. Verify Email OTP

```
POST /api/v1/pharmacy/register/verify-email
```

**Authentication:** None (public endpoint)
**Rate Limit:** 10 req/min per IP

**Request Body (application/json):**
```json
{
  "email": "string - required, email address used during registration",
  "otp": "string - required, 6-digit OTP sent to email"
}
```

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "pharmacy_id": "uuid-v4",
    "email_verified": true,
    "access_token": "JWT string - pharmacy_owner role, 24h expiry",
    "refresh_token": "JWT string - 30d expiry",
    "status": "PENDING_KYC",
    "next_step": "UPLOAD_KYC_DOCUMENTS",
    "message": "Email verified. Please upload your KYC documents to proceed."
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `INVALID_OTP` | OTP does not match |
| 400 | `OTP_EXPIRED` | OTP older than 15 minutes |
| 400 | `OTP_MAX_ATTEMPTS` | More than 3 failed OTP attempts; new OTP must be requested |
| 404 | `EMAIL_NOT_FOUND` | Email not found in pending registrations |
| 409 | `EMAIL_ALREADY_VERIFIED` | Email already verified for this account |
| 429 | `RATE_LIMIT_EXCEEDED` | Too many verify attempts (IP rate limit) |

---

### 3. Resend Email OTP

```
POST /api/v1/pharmacy/register/resend-otp
```

**Authentication:** None (public endpoint)
**Rate Limit:** 3 req/min per email

**Request Body (application/json):**
```json
{
  "email": "string - required"
}
```

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "message": "New OTP sent to your email.",
    "retry_after_seconds": 60,
    "resends_remaining": 4
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 404 | `EMAIL_NOT_FOUND` | Email not in pending registrations |
| 409 | `EMAIL_ALREADY_VERIFIED` | Already verified |
| 429 | `RESEND_LIMIT_EXCEEDED` | Max 5 resends per session reached |
| 429 | `RESEND_TOO_SOON` | 60-second cooldown not elapsed |
| 429 | `RATE_LIMIT_EXCEEDED` | Too many resend attempts (email rate limit) |

---

### 4. Get Registration & KYC Status

```
GET /api/v1/pharmacy/registration-status
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 60 req/min

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "pharmacy_id": "uuid-v4",
    "business_name": "Sharma Medical Store",
    "status": "PENDING_KYC",
    "plan": "FREE",
    "email_verified": true,
    "kyc": {
      "documents_uploaded": 3,
      "documents_required": 5,
      "documents_verified": 0,
      "documents_rejected": 0,
      "submitted_at": null,
      "reviewed_at": null,
      "rejection_reason": null,
      "can_reapply": true,
      "next_step": "UPLOAD_REMAINING_DOCUMENTS"
    },
    "profile_completeness_pct": 45,
    "created_at": "2026-07-24T00:00:00Z"
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 401 | `UNAUTHORIZED` | Missing or invalid JWT |
| 403 | `FORBIDDEN` | User is not a pharmacy_owner or pharmacy_staff |
| 429 | `RATE_LIMIT_EXCEEDED` | Too many status requests |

---

## Data Models

### Pharmacy

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID v4 | PK, not null | Unique pharmacy identifier |
| `business_name` | VARCHAR(120) | Not null, unique per state+pincode | Registered pharmacy/business name |
| `owner_name` | VARCHAR(100) | Not null | Full legal name of owner/director |
| `phone` | VARCHAR(15) | Not null, unique, format +91XXXXXXXXXX | Primary contact phone |
| `email` | VARCHAR(255) | Not null, unique, lowercase | Primary email for owner |
| `password_hash` | TEXT | Not null | Bcrypt hash of password |
| `business_type` | ENUM | PHARMACY \| HOSPITAL \| CLINIC_PHARMACY | Type of business entity |
| `address` | JSONB | Not null | `{ flat, area, city, state, pincode, latitude, longitude }` |
| `status` | ENUM | Not null, default PENDING_KYC | PENDING_KYC \| KYC_SUBMITTED \| ACTIVE \| SUSPENDED \| REJECTED |
| `plan` | ENUM | Not null, default FREE | FREE \| STARTER \| GROWTH \| PRO |
| `plan_expires_at` | TIMESTAMPTZ | Nullable | Null for FREE plan; date plan expires for paid plans |
| `gstin` | VARCHAR(15) | Not null, unique | GST Identification Number |
| `drug_licence_number` | VARCHAR(50) | Not null | State drug authority licence number |
| `fssai_number` | VARCHAR(14) | Nullable | FSSAI food business operator number |
| `pan_number` | VARCHAR(10) | Not null, unique | Permanent Account Number |
| `commission_pct` | DECIMAL(5,2) | Not null, default 8.00, range 3-20 | Platform commission percentage |
| `zone_id` | UUID | FK ? Zone.id, nullable | Assigned delivery zone (set at KYC approval) |
| `is_online` | BOOLEAN | Not null, default false | Whether pharmacy is visible on customer app |
| `email_verified` | BOOLEAN | Not null, default false | Whether email OTP verified |
| `can_reapply` | BOOLEAN | Not null, default true | Whether rejected pharmacy can reapply |
| `created_at` | TIMESTAMPTZ | Not null, default now() | Record creation timestamp |
| `updated_at` | TIMESTAMPTZ | Not null, default now() | Last update timestamp |

### User

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID v4 | PK | Unique user identifier |
| `pharmacy_id` | UUID | FK ? Pharmacy.id, nullable | Linked pharmacy (null for admin/rider users) |
| `role` | ENUM | Not null | customer \| pharmacy_owner \| pharmacy_staff \| rider \| admin_* |
| `name` | VARCHAR(100) | Not null | Full display name |
| `phone` | VARCHAR(15) | Not null, unique | Mobile number |
| `email` | VARCHAR(255) | Not null, unique | Email address |
| `password_hash` | TEXT | Not null | Bcrypt hash |
| `is_active` | BOOLEAN | Not null, default true | Account active flag |
| `created_at` | TIMESTAMPTZ | Not null | Record creation |

---

## Acceptance Criteria

- [ ] **Given** a valid registration payload with all required fields, **when** POST `/api/v1/pharmacy/register` is called, **then** a `Pharmacy` record with `status=PENDING_KYC` and `plan=FREE` is created, a `pharmacy_owner` User is created and linked, and a 201 response is returned with `pharmacy_id`.
- [ ] **Given** a GSTIN with an invalid checksum digit, **when** registration is attempted, **then** the API returns HTTP 400 with error code `INVALID_GSTIN` and no records are persisted.
- [ ] **Given** an email that already exists in the platform (any role), **when** registration is attempted with that email, **then** the API returns HTTP 409 `EMAIL_ALREADY_REGISTERED`.
- [ ] **Given** a successfully registered pharmacy, **when** the correct 6-digit OTP is submitted to `/register/verify-email`, **then** `email_verified=true` is set and a JWT access token with `pharmacy_owner` role is returned.
- [ ] **Given** an OTP submitted after its 15-minute expiry, **when** `/register/verify-email` is called, **then** HTTP 400 `OTP_EXPIRED` is returned and no token is issued.
- [ ] **Given** a pharmacy with `status=PENDING_KYC`, **when** GET `/api/v1/pharmacy/registration-status` is called with its JWT, **then** the response includes `kyc.documents_required=5`, `kyc.next_step`, and correct `profile_completeness_pct`.
- [ ] **Given** the same Drug Licence number attempted in the same state, **when** a second registration is submitted, **then** HTTP 409 `DRUG_LICENCE_ALREADY_REGISTERED` is returned.
- [ ] **Given** a phone number not in +91[6-9]XXXXXXXXX format, **when** registration is submitted, **then** HTTP 400 `INVALID_PHONE` is returned.

---

## Dependencies

- EPIC-001 / STORY-001 - JWT issuance and role-based token generation
- EPIC-001 / STORY-002 - OTP generation and email delivery service
- EPIC-002 / STORY-001 - Email notification template for OTP
- EPIC-007 / STORY-001 - FREE plan initialisation on pharmacy creation
- Pincode reference table - must be seeded with all valid Indian pincodes

---

## Notes

- GSTIN checksum algorithm: convert each character to its value in base-36 (0-9=0-9, A-Z=10-35), multiply alternating positions by 1 and 2, sum all, modulo 36, map back to alphanumeric; the result must equal the 15th character.
- Password is hashed using bcrypt with work factor 12 before storage; plaintext is never logged.
- The `address.latitude` and `address.longitude` should be auto-filled from a geocoding service using the pincode if not provided by the client; they are used for zone assignment and distance calculations.
- Registration OTP email must be delivered within 30 seconds; use a priority queue lane for OTP delivery.
- If FSSAI number is not yet obtained (common for new pharmacies), it can be submitted later as part of KYC documents without blocking initial registration.
- All registration attempts (success and failure) are logged in an `AuditLog` table with IP address, timestamp, and outcome.
