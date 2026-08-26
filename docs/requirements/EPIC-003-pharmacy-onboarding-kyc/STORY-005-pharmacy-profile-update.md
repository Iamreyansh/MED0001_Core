# STORY-003-005: Pharmacy Profile Update

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-003-005 |
| **Epic** | EPIC-003 - Pharmacy Onboarding & KYC |
| **Priority** | P1 |
| **Complexity** | M |
| **Status** | Draft |

---

## Overview

This story covers self-service management of a pharmacy's profile after account creation - including business details, operating hours, tax and compliance settings, profile completeness scoring, and bank account setup with penny-drop verification. An accurate, complete profile is essential for customer trust, GST compliance, and payout processing. Certain sensitive changes (business name, phone, GSTIN) require admin approval or re-verification. Profile completeness is surfaced as a percentage with a missing-fields checklist, shown persistently in the pharmacy dashboard until 100% is reached. Bank account details are verified via a Rs 1 penny-drop transfer before payouts are enabled.

---

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| `pharmacy_owner` | Read, Write | Can view and update all profile sections; bank account owner |
| `pharmacy_staff` | Read | Can view profile; cannot update anything |
| `admin_super` | Read, Write | Can update any pharmacy profile with audit trail |
| `admin_operations` | Read, Write | Can update profile; changes to business_name require admin_super |
| `admin_finance` | Read | Can view tax and bank details; cannot update |

---

## Business Rules

1. **Business name changes require admin approval**: If `business_name` is changed via PATCH `/pharmacy/profile`, the change is not applied immediately. Instead, a `ProfileChangeRequest` record is created with `status=PENDING_APPROVAL`. The current name remains active until admin approves. The pharmacy owner is notified of the review.
2. **Phone changes require OTP verification**: PATCH `/pharmacy/profile` with a new `phone` value triggers a 6-digit OTP to the new phone number. The phone is only updated after OTP is verified via a separate step. The old phone remains active during this process.
3. **Logo size and format constraints**: Logo uploads must be PNG or JPG, maximum 2 MB. Logo is uploaded to the CDN; the returned `logo_url` is the CDN URL. Non-conforming files return `INVALID_LOGO`.
4. **GSTIN changes require re-verification**: If `gstin` is updated via PATCH `/pharmacy/profile/tax`, auto-KYC for the GSTIN check is re-triggered and the pharmacy is temporarily flagged as `PENDING_GSTIN_REVERIFICATION` in admin. Business remains operational during re-verification.
5. **Bank account is verified via penny drop**: Saving a bank account (POST `/pharmacy/profile/bank-account`) initiates a Rs 1 transfer to the provided account via CashfreePayout. If the transfer succeeds and account confirms, `verification_status=VERIFIED`. If it fails within 24 hours, status is `FAILED` and the pharmacy must re-enter details.
6. **Profile completeness < 100% shows persistent dashboard reminder**: The completeness score is recalculated on every profile update. Fields contributing to 100%: business_name, phone, email, logo, address (all sub-fields), gstin, drug_licence_number, fssai_number, pan_number, operating_hours (at least 5 days configured), bank_account (verified), tagline, registered_pharmacist_name.
7. **Operating hours are per-day-of-week**: `operating_hours` is an array of 7 entries (one per day, 0=Monday to 6=Sunday). Each entry has `open_time` (HH:MM 24h), `close_time` (HH:MM 24h), and `is_closed` (boolean). `open_time` must be before `close_time` unless `is_closed=true`. Overlapping or missing days are not permitted.
8. **Admin profile edits are audit-logged**: Any profile field changed by an admin (not the pharmacy owner) is written to `AuditLog` with `actor_id`, `actor_role`, `changed_fields`, `old_values`, and `new_values`.

---

## API Endpoints

### 1. Get Pharmacy Profile

```
GET /api/v1/pharmacy/profile
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 120 req/min

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "pharmacy_id": "uuid-v4",
    "code": "PHM-0042",
    "business_name": "Sharma Medical Store",
    "tagline": "Your neighbourhood pharmacy",
    "logo_url": "https://cdn.example.com/logos/phm-0042.png",
    "phone": "+919876543210",
    "email": "rajesh@sharma.com",
    "business_type": "PHARMACY",
    "address": {
      "flat": "12",
      "area": "Koramangala 4th Block",
      "city": "Bengaluru",
      "state": "Karnataka",
      "pincode": "560034",
      "latitude": 12.9352,
      "longitude": 77.6245
    },
    "operating_hours": [
      { "day_of_week": 0, "day_name": "Monday", "open_time": "09:00", "close_time": "21:00", "is_closed": false },
      { "day_of_week": 1, "day_name": "Tuesday", "open_time": "09:00", "close_time": "21:00", "is_closed": false },
      { "day_of_week": 6, "day_name": "Sunday", "open_time": null, "close_time": null, "is_closed": true }
    ],
    "tax": {
      "gstin": "29AABCS1429B1ZB",
      "pan_number": "AABCS1429B",
      "drug_licence_number": "KA/DL/2024/12345",
      "fssai_number": "11223344556677",
      "is_gst_registered": true,
      "e_invoicing_enabled": false,
      "tds_applicable": false,
      "tcs_applicable": true,
      "registered_pharmacist_name": "Dr. Rajesh Sharma"
    },
    "bank_account": {
      "account_holder": "Sharma Medical Store",
      "bank_name": "HDFC Bank",
      "account_number_masked": "XXXXXXXXXXXX4321",
      "ifsc_code": "HDFC0001234",
      "account_type": "CURRENT",
      "verification_status": "VERIFIED",
      "verified_at": "2026-07-20T10:00:00Z"
    },
    "profile_completeness_pct": 92,
    "status": "ACTIVE",
    "plan": "GROWTH",
    "created_at": "2026-07-01T00:00:00Z",
    "updated_at": "2026-07-24T00:00:00Z"
  },
  "meta": {}
}
```

---

### 2. Update Business Details

```
PATCH /api/v1/pharmacy/profile
```

**Authentication:** Bearer JWT - `pharmacy_owner`
**Rate Limit:** 20 req/min

**Request Body (application/json):**
```json
{
  "business_name": "string - optional, 2-120 chars; triggers admin approval flow if changed",
  "tagline": "string - optional, max 200 chars",
  "logo_url": "string - optional, CDN URL of uploaded logo (upload separately via signed URL); must be PNG/JPG, max 2MB",
  "phone": "string - optional, +91XXXXXXXXXX; triggers OTP verification if changed",
  "email": "string - optional, valid email; triggers OTP verification if changed",
  "address": {
    "flat": "string - optional",
    "area": "string - optional",
    "city": "string - optional",
    "state": "string - optional",
    "pincode": "string - optional, exactly 6 digits",
    "latitude": "number - optional",
    "longitude": "number - optional"
  },
  "operating_hours": [
    {
      "day_of_week": "integer - 0 (Monday) to 6 (Sunday)",
      "open_time": "string - HH:MM 24h format, null if is_closed=true",
      "close_time": "string - HH:MM 24h format, null if is_closed=true",
      "is_closed": "boolean"
    }
  ]
}
```

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "pharmacy_id": "uuid-v4",
    "updated_fields": ["tagline", "operating_hours"],
    "pending_approval_fields": ["business_name"],
    "pending_verification_fields": [],
    "profile_completeness_pct": 95,
    "message": "Profile updated. business_name change is pending admin approval."
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `INVALID_OPERATING_HOURS` | open_time >= close_time on a non-closed day; or missing days |
| 400 | `INVALID_LOGO` | Logo URL is not PNG/JPG or exceeds 2MB |
| 400 | `INVALID_PINCODE` | Pincode not a valid 6-digit Indian pincode |
| 400 | `INVALID_PHONE` | Phone not in +91XXXXXXXXXX format |
| 403 | `PHARMACY_NOT_ACTIVE` | Pharmacy is not in ACTIVE status |

---

### 3. Update Tax & Compliance Details

```
PATCH /api/v1/pharmacy/profile/tax
```

**Authentication:** Bearer JWT - `pharmacy_owner`
**Rate Limit:** 10 req/min

**Request Body (application/json):**
```json
{
  "gstin": "string - optional, 15 alphanumeric; triggers GSTIN re-verification if changed",
  "pan_number": "string - optional, 10 chars PAN format",
  "drug_licence_number": "string - optional, max 50 chars",
  "fssai_number": "string - optional, 14-digit FSSAI number",
  "is_gst_registered": "boolean - optional",
  "e_invoicing_enabled": "boolean - optional; only relevant for turnover > Rs 5Cr",
  "tds_applicable": "boolean - optional; TDS under Section 194Q",
  "tcs_applicable": "boolean - optional; TCS under Section 206C(1H)",
  "registered_pharmacist_name": "string - optional, max 100 chars, name of registered pharmacist on licence"
}
```

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "pharmacy_id": "uuid-v4",
    "updated_fields": ["registered_pharmacist_name", "e_invoicing_enabled"],
    "re_verification_triggered": false,
    "profile_completeness_pct": 96,
    "message": "Tax details updated successfully."
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `INVALID_GSTIN` | GSTIN fails format or checksum validation |
| 400 | `INVALID_PAN` | PAN format invalid |

---

### 4. Get Profile Completeness

```
GET /api/v1/pharmacy/profile/completeness
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 60 req/min

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "pharmacy_id": "uuid-v4",
    "completeness_pct": 85,
    "missing_fields": [
      { "field": "logo_url", "label": "Pharmacy Logo", "impact_pct": 5, "action": "Upload your pharmacy logo to build customer trust." },
      { "field": "fssai_number", "label": "FSSAI Number", "impact_pct": 5, "action": "Add your FSSAI licence number for compliance." },
      { "field": "operating_hours", "label": "Operating Hours", "impact_pct": 5, "action": "Configure operating hours for at least 5 days of the week." }
    ],
    "completed_fields": [
      "business_name", "phone", "email", "address", "gstin",
      "drug_licence_number", "pan_number", "bank_account_verified",
      "registered_pharmacist_name", "tagline"
    ]
  },
  "meta": {}
}
```

---

### 5. Save Bank Account

```
POST /api/v1/pharmacy/profile/bank-account
```

**Authentication:** Bearer JWT - `pharmacy_owner`
**Rate Limit:** 5 req/min per pharmacy

**Request Body (application/json):**
```json
{
  "account_holder": "string - required, max 100 chars, name as on bank account",
  "bank_name": "string - required, max 100 chars",
  "account_number": "string - required, 9-18 digits",
  "ifsc_code": "string - required, 11 chars, format [A-Z]{4}0[A-Z0-9]{6}",
  "account_type": "string - required, enum: CURRENT | SAVINGS"
}
```

**Success Response - 201 Created:**
```json
{
  "success": true,
  "data": {
    "bank_account_id": "uuid-v4",
    "account_holder": "Sharma Medical Store",
    "bank_name": "HDFC Bank",
    "account_number_masked": "XXXXXXXXXXXX4321",
    "ifsc_code": "HDFC0001234",
    "account_type": "CURRENT",
    "verification_status": "PENDING",
    "penny_drop_initiated": true,
    "estimated_verification_hours": 24,
    "message": "Bank account saved. A Re 1 test transfer has been initiated. You will be notified once verified."
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `INVALID_IFSC` | IFSC code format invalid or not found in RBI IFSC registry |
| 400 | `INVALID_ACCOUNT_NUMBER` | Account number not 9-18 digits |
| 409 | `BANK_ACCOUNT_ALREADY_VERIFIED` | A verified bank account already exists; contact support to change |

---

### 6. Get Bank Account Details

```
GET /api/v1/pharmacy/profile/bank-account
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `admin_finance`, `admin_super`
**Rate Limit:** 60 req/min

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "bank_account_id": "uuid-v4",
    "account_holder": "Sharma Medical Store",
    "bank_name": "HDFC Bank",
    "account_number_masked": "XXXXXXXXXXXX4321",
    "ifsc_code": "HDFC0001234",
    "account_type": "CURRENT",
    "verification_status": "VERIFIED",
    "verified_at": "2026-07-20T10:00:00Z",
    "penny_drop_reference": "RZP-PENNY-XXXXX"
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 404 | `BANK_ACCOUNT_NOT_FOUND` | No bank account saved for this pharmacy |

---

## Data Models

### PharmacyBankAccount

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID v4 | PK | Unique bank account record |
| `pharmacy_id` | UUID | FK ? Pharmacy.id, not null, unique | One active bank account per pharmacy |
| `account_holder` | VARCHAR(100) | Not null | Name as on bank account |
| `bank_name` | VARCHAR(100) | Not null | Bank name |
| `account_number_encrypted` | TEXT | Not null | AES-256 encrypted account number |
| `account_number_last4` | CHAR(4) | Not null | Last 4 digits for display |
| `ifsc_code` | CHAR(11) | Not null | RBI-registered IFSC code |
| `account_type` | ENUM | Not null | CURRENT \| SAVINGS |
| `verification_status` | ENUM | Not null, default PENDING | PENDING \| VERIFIED \| FAILED |
| `penny_drop_reference` | VARCHAR(100) | Nullable | CashfreePayout payout reference ID |
| `verified_at` | TIMESTAMPTZ | Nullable | Timestamp when verification completed |
| `created_at` | TIMESTAMPTZ | Not null, default now() | Record creation |

### PharmacyOperatingHours

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID v4 | PK | Record ID |
| `pharmacy_id` | UUID | FK ? Pharmacy.id, not null | Owning pharmacy |
| `day_of_week` | SMALLINT | Not null, 0-6 (0=Monday) | Day of week |
| `open_time` | TIME | Nullable | Opening time (null if is_closed) |
| `close_time` | TIME | Nullable | Closing time (null if is_closed) |
| `is_closed` | BOOLEAN | Not null, default false | Whether pharmacy is closed this day |

### ProfileChangeRequest

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID v4 | PK | Request ID |
| `pharmacy_id` | UUID | FK ? Pharmacy.id, not null | Requesting pharmacy |
| `field_name` | VARCHAR(100) | Not null | Name of the field being changed |
| `old_value` | TEXT | Not null | Current field value (serialised) |
| `new_value` | TEXT | Not null | Requested new value (serialised) |
| `status` | ENUM | Not null, default PENDING_APPROVAL | PENDING_APPROVAL \| APPROVED \| REJECTED |
| `reviewed_by` | UUID | FK ? User.id, nullable | Admin who actioned the request |
| `reviewed_at` | TIMESTAMPTZ | Nullable | Review timestamp |
| `created_at` | TIMESTAMPTZ | Not null, default now() | Request creation time |

---

## Acceptance Criteria

- [ ] **Given** a pharmacy_owner calls PATCH `/api/v1/pharmacy/profile` with a new `business_name`, **then** the change is NOT immediately applied; a `ProfileChangeRequest` is created with `status=PENDING_APPROVAL`, and the response lists `business_name` in `pending_approval_fields`.
- [ ] **Given** a pharmacy_owner calls PATCH `/api/v1/pharmacy/profile` with a new `phone`, **then** an OTP is sent to the new phone number and the phone is not updated until OTP verification succeeds.
- [ ] **Given** `operating_hours` is submitted with Monday `open_time=21:00` and `close_time=09:00` (open after close), **when** the request is processed, **then** HTTP 400 `INVALID_OPERATING_HOURS` is returned.
- [ ] **Given** POST `/api/v1/pharmacy/profile/bank-account` is called with valid bank details, **then** a penny drop of Rs 1 is initiated via CashfreePayout, the account `verification_status=PENDING`, and the pharmacy owner is notified when verification completes.
- [ ] **Given** a verified bank account already exists, **when** POST `/api/v1/pharmacy/profile/bank-account` is called again, **then** HTTP 409 `BANK_ACCOUNT_ALREADY_VERIFIED` is returned.
- [ ] **Given** PATCH `/api/v1/pharmacy/profile/tax` is called with a new valid `gstin`, **then** GSTIN re-verification is triggered via auto-KYC, and `re_verification_triggered=true` is returned in the response.
- [ ] **Given** a pharmacy has 3 out of 12 profile fields completed, **when** GET `/api/v1/pharmacy/profile/completeness` is called, **then** `completeness_pct` correctly reflects the proportion and `missing_fields` lists the specific incomplete fields with `impact_pct` and actionable `action` strings.
- [ ] **Given** an admin updates a pharmacy's profile field, **then** an `AuditLog` entry is written with `actor_id`, `actor_role`, `changed_fields`, `old_values`, and `new_values`.

---

## Dependencies

- STORY-003-001 - Pharmacy registration (pharmacy record must exist)
- STORY-003-003 - Auto-KYC (re-triggered on GSTIN change)
- EPIC-001 / STORY-002 - OTP service (phone and email change verification)
- EPIC-002 / STORY-001 - Notification service (bank account verification result, profile change approval)
- External: CashfreePayout - Penny drop payout API for bank account verification
- External: RBI IFSC Registry - IFSC code validation

---

## Notes

- Account number is stored AES-256 encrypted using a KMS-managed key. Only the last 4 digits are stored in plaintext for display.
- Logo upload flow: the pharmacy dashboard calls a separate pre-signed upload URL endpoint (not defined here; part of EPIC-001 media upload service). After uploading the file, the CDN URL is sent to PATCH `/pharmacy/profile` as `logo_url`.
- Profile completeness weights: each contributing field is weighted equally at 100/N%. Current N=12 contributing fields; if fields are added in future, the denominator scales automatically.
- Penny drop verification timing: initiate immediately on POST. Listen for CashfreePayout webhook `payout.processed` or `payout.failed`. If no webhook within 24 hours, mark as `FAILED` and notify pharmacy.
- Operating hours are used by the customer app to show "Open Now" / "Closed" status and by the delivery routing engine for order assignment. Cache operating hours in Redis with 5-minute TTL.
