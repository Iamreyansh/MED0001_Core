# STORY-003-002: KYC Document Upload

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-003-002 |
| **Epic** | EPIC-003 - Pharmacy Onboarding & KYC |
| **Priority** | P0 |
| **Complexity** | M |
| **Status** | Draft |

---

## Overview

This story covers the KYC document management flow for pharmacy owners: uploading compliance documents (Drug Licence, GSTIN Certificate, FSSAI, PAN Card, Bank Statement, Proprietor ID), viewing status of each document, replacing rejected documents, and submitting the complete set for review. The admin side of this story handles viewing document bundles, downloading signed URLs, and marking individual documents as verified or rejected. Document files are stored in a private cloud bucket with time-limited signed URLs. Once all required documents are submitted, the system notifies the admin team and optionally triggers auto-KYC verification (STORY-003-003).

---

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| `pharmacy_owner` | Create, Read, Delete | Upload, view, replace own pharmacy's documents; submit for review |
| `pharmacy_staff` | Read | View document status only (cannot upload or submit) |
| `admin_super` | Read, Update | View all pharmacy documents, verify/reject any document |
| `admin_operations` | Read, Update | View and action KYC documents in review queue |
| `admin_compliance` | Read, Update | Specialist access for schedule/compliance document verification |

---

## Business Rules

1. **Accepted file types and size**: Only PDF, JPG, and PNG files are accepted. Maximum file size is 10 MB per document (server rejects with `FILE_TOO_LARGE` if exceeded). Files are virus-scanned before storage; infected files are rejected with `FILE_SCAN_FAILED`.
2. **Five core documents are required before submission**: `GSTIN_CERTIFICATE`, `DRUG_LICENCE`, `FSSAI_CERTIFICATE`, `PAN_CARD`, and one of `BANK_STATEMENT` or `PROPRIETOR_ID` are the five mandatory document types. Calling `/kyc/submit` with any missing document returns `DOCUMENTS_INCOMPLETE` listing the missing types.
3. **Signed URL expiry**: All file URLs returned to clients (pharmacy or admin) are pre-signed with a 1-hour expiry. The backend never exposes permanent storage URLs. Clients must re-request document list to obtain a fresh signed URL after expiry.
4. **Drug Licence must include expiry date**: When uploading a `DRUG_LICENCE` document, the `expiry_date` field in the request body is required. If not provided, the upload is rejected with `EXPIRY_DATE_REQUIRED`. Expiry alerts are scheduled immediately: notifications are queued at T-60 days and T-30 days before expiry.
5. **Only one active document per type**: Each `document_type` can have at most one document in non-REJECTED status at a time. Uploading a new document for a type where one already exists in `UPLOADED` or `UNDER_REVIEW` status is rejected with `DOCUMENT_TYPE_ALREADY_PENDING`. If the existing document is `VERIFIED`, the type is locked and cannot be re-uploaded unless explicitly unlocked by admin.
6. **Rejected documents can be replaced**: A document with `status=REJECTED` can be deleted and re-uploaded. Deleting a `VERIFIED` document requires admin action. Deleting an `UNDER_REVIEW` document is not permitted; contact admin to reject it first.
7. **Submission triggers notifications**: Calling `/kyc/submit` when all required documents are uploaded transitions pharmacy status from `PENDING_KYC` to `KYC_SUBMITTED` and sends an in-app + email notification to the admin operations team. If `kyc_auto_verification_enabled` feature flag is ON, auto-KYC is triggered simultaneously (STORY-003-003).
8. **Re-submission after rejection**: If a pharmacy's KYC is rejected at the admin review stage (not document-level), the pharmacy can replace rejected documents and call `/kyc/submit` again, resetting to `KYC_SUBMITTED`. Allowed only if `can_reapply=true` on the Pharmacy record.
9. **Admin download tracking**: Every admin access to a signed document URL is logged in the audit trail with admin_id, document_id, and timestamp.
10. **FSSAI is conditionally optional**: For `business_type=CLINIC_PHARMACY`, `FSSAI_CERTIFICATE` is recommended but not blocking for submission; for `PHARMACY` and `HOSPITAL`, it is mandatory.

---

## API Endpoints

### 1. Upload KYC Document

```
POST /api/v1/pharmacy/kyc/documents
```

**Authentication:** Bearer JWT - `pharmacy_owner`
**Rate Limit:** 20 req/min per pharmacy

**Request Body (multipart/form-data):**
```json
{
  "document_type": "string - required, enum: GSTIN_CERTIFICATE | DRUG_LICENCE | FSSAI_CERTIFICATE | PAN_CARD | BANK_STATEMENT | PROPRIETOR_ID",
  "file": "binary - required, PDF/JPG/PNG, max 10MB",
  "expiry_date": "string - required only when document_type=DRUG_LICENCE or FSSAI_CERTIFICATE, format YYYY-MM-DD"
}
```

**Success Response - 201 Created:**
```json
{
  "success": true,
  "data": {
    "document_id": "uuid-v4",
    "document_type": "DRUG_LICENCE",
    "status": "UPLOADED",
    "file_name": "drug-licence.pdf",
    "file_size_bytes": 524288,
    "expiry_date": "2027-06-30",
    "uploaded_at": "2026-07-24T00:00:00Z",
    "signed_url": "https://storage.example.com/kyc/...?expires=1h",
    "signed_url_expires_at": "2026-07-24T01:00:00Z"
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `INVALID_DOCUMENT_TYPE` | `document_type` not in allowed enum |
| 400 | `INVALID_FILE_TYPE` | File is not PDF, JPG, or PNG |
| 400 | `FILE_TOO_LARGE` | File exceeds 10 MB |
| 400 | `EXPIRY_DATE_REQUIRED` | Drug Licence or FSSAI uploaded without `expiry_date` |
| 400 | `EXPIRY_DATE_IN_PAST` | Provided `expiry_date` is before today |
| 400 | `FILE_SCAN_FAILED` | Virus scan rejected the file |
| 403 | `PHARMACY_ALREADY_ACTIVE` | KYC already approved; cannot upload new docs without admin unlock |
| 409 | `DOCUMENT_TYPE_ALREADY_PENDING` | An UPLOADED or UNDER_REVIEW document of this type already exists |

---

### 2. List KYC Documents

```
GET /api/v1/pharmacy/kyc/documents
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 60 req/min

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "pharmacy_id": "uuid-v4",
    "kyc_status": "KYC_SUBMITTED",
    "submitted_at": "2026-07-23T10:00:00Z",
    "documents": [
      {
        "document_id": "uuid-v4",
        "document_type": "DRUG_LICENCE",
        "status": "UNDER_REVIEW",
        "rejection_reason": null,
        "expiry_date": "2027-06-30",
        "uploaded_at": "2026-07-23T08:00:00Z",
        "signed_url": "https://storage.example.com/kyc/...?expires=1h",
        "signed_url_expires_at": "2026-07-24T01:00:00Z"
      },
      {
        "document_id": "uuid-v4",
        "document_type": "GSTIN_CERTIFICATE",
        "status": "VERIFIED",
        "rejection_reason": null,
        "expiry_date": null,
        "uploaded_at": "2026-07-23T08:05:00Z",
        "signed_url": "https://storage.example.com/kyc/...?expires=1h",
        "signed_url_expires_at": "2026-07-24T01:00:00Z"
      },
      {
        "document_id": "uuid-v4",
        "document_type": "PAN_CARD",
        "status": "REJECTED",
        "rejection_reason": "Image is blurry, please re-upload a clear scan.",
        "expiry_date": null,
        "uploaded_at": "2026-07-22T12:00:00Z",
        "signed_url": "https://storage.example.com/kyc/...?expires=1h",
        "signed_url_expires_at": "2026-07-24T01:00:00Z"
      }
    ],
    "required_documents": ["GSTIN_CERTIFICATE", "DRUG_LICENCE", "FSSAI_CERTIFICATE", "PAN_CARD", "BANK_STATEMENT"],
    "missing_documents": ["FSSAI_CERTIFICATE", "BANK_STATEMENT"],
    "ready_to_submit": false
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 401 | `UNAUTHORIZED` | Missing or invalid JWT |
| 403 | `FORBIDDEN` | User role cannot access this pharmacy's documents |

---

### 3. Delete / Replace a KYC Document

```
DELETE /api/v1/pharmacy/kyc/documents/:document_id
```

**Authentication:** Bearer JWT - `pharmacy_owner`
**Rate Limit:** 20 req/min per pharmacy

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| `document_id` | UUID | ID of the document to delete |

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "document_id": "uuid-v4",
    "deleted": true,
    "message": "Document removed. You can now upload a replacement."
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 403 | `CANNOT_DELETE_VERIFIED` | Document is VERIFIED; admin unlock required |
| 403 | `CANNOT_DELETE_UNDER_REVIEW` | Document is UNDER_REVIEW; ask admin to reject first |
| 404 | `DOCUMENT_NOT_FOUND` | document_id not found for this pharmacy |

---

### 4. Submit KYC for Review

```
POST /api/v1/pharmacy/kyc/submit
```

**Authentication:** Bearer JWT - `pharmacy_owner`
**Rate Limit:** 5 req/min per pharmacy

**Request Body (application/json):**
```json
{}
```
*(No body required; all documents already uploaded)*

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "pharmacy_id": "uuid-v4",
    "status": "KYC_SUBMITTED",
    "submitted_at": "2026-07-24T00:00:00Z",
    "auto_kyc_triggered": true,
    "estimated_review_hours": 24,
    "message": "Your KYC documents have been submitted for review. You will be notified via email and WhatsApp."
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `DOCUMENTS_INCOMPLETE` | One or more required document types are missing or REJECTED |
| 400 | `EMAIL_NOT_VERIFIED` | Pharmacy owner email not yet verified |
| 409 | `ALREADY_SUBMITTED` | KYC already in KYC_SUBMITTED status |
| 409 | `CANNOT_REAPPLY` | Pharmacy was rejected with `can_reapply=false` |
| 409 | `ALREADY_ACTIVE` | Pharmacy already ACTIVE; no submission needed |

---

### 5. Admin - View Pharmacy KYC Documents

```
GET /api/v1/admin/pharmacies/:id/kyc
```

**Authentication:** Bearer JWT - `admin_super`, `admin_operations`, `admin_compliance`
**Rate Limit:** 120 req/min

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| `id` | UUID | Pharmacy ID |

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "pharmacy_id": "uuid-v4",
    "business_name": "Sharma Medical Store",
    "kyc_status": "KYC_SUBMITTED",
    "submitted_at": "2026-07-24T00:00:00Z",
    "auto_kyc_result": {
      "gstin": "PASS",
      "drug_licence": "PENDING",
      "fssai": "PASS"
    },
    "documents": [
      {
        "document_id": "uuid-v4",
        "document_type": "DRUG_LICENCE",
        "status": "UNDER_REVIEW",
        "rejection_reason": null,
        "expiry_date": "2027-06-30",
        "uploaded_at": "2026-07-23T08:00:00Z",
        "signed_url": "https://storage.example.com/kyc/...?expires=1h",
        "signed_url_expires_at": "2026-07-24T01:00:00Z",
        "verified_by": null,
        "verified_at": null
      }
    ]
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 403 | `FORBIDDEN` | Caller is not an admin role |
| 404 | `PHARMACY_NOT_FOUND` | Pharmacy ID does not exist |

---

### 6. Admin - Verify or Reject a Document

```
POST /api/v1/admin/pharmacies/:id/kyc/documents/:doc_id/verify
```

**Authentication:** Bearer JWT - `admin_super`, `admin_operations`, `admin_compliance`
**Rate Limit:** 60 req/min

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| `id` | UUID | Pharmacy ID |
| `doc_id` | UUID | Document ID |

**Request Body (application/json):**
```json
{
  "verified": "boolean - required; true to verify, false to reject",
  "rejection_reason": "string - required when verified=false; max 500 chars, human-readable explanation shown to pharmacy"
}
```

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "document_id": "uuid-v4",
    "document_type": "PAN_CARD",
    "status": "REJECTED",
    "rejection_reason": "Image is blurry, please re-upload a clear scan.",
    "verified_by": "admin-uuid-v4",
    "verified_at": "2026-07-24T00:05:00Z"
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `REJECTION_REASON_REQUIRED` | `verified=false` but `rejection_reason` is empty |
| 404 | `DOCUMENT_NOT_FOUND` | Document ID not found under this pharmacy |
| 409 | `DOCUMENT_ALREADY_VERIFIED` | Document already in VERIFIED status |

---

## Data Models

### KycDocument

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID v4 | PK, not null | Unique document identifier |
| `pharmacy_id` | UUID | FK ? Pharmacy.id, not null | Owning pharmacy |
| `document_type` | ENUM | Not null | GSTIN_CERTIFICATE \| DRUG_LICENCE \| FSSAI_CERTIFICATE \| PAN_CARD \| BANK_STATEMENT \| PROPRIETOR_ID |
| `file_key` | TEXT | Not null | S3/cloud storage object key (internal path, never exposed) |
| `file_name` | VARCHAR(255) | Not null | Original uploaded filename |
| `file_size_bytes` | INTEGER | Not null | File size in bytes |
| `file_mime_type` | VARCHAR(50) | Not null | MIME type: application/pdf, image/jpeg, image/png |
| `status` | ENUM | Not null, default UPLOADED | UPLOADED \| UNDER_REVIEW \| VERIFIED \| REJECTED |
| `rejection_reason` | TEXT | Nullable | Admin-provided reason; shown to pharmacy if rejected |
| `expiry_date` | DATE | Nullable | Licence expiry date (required for Drug Licence, FSSAI) |
| `verified_by` | UUID | FK ? User.id, nullable | Admin who verified/rejected the document |
| `verified_at` | TIMESTAMPTZ | Nullable | Timestamp of admin action |
| `created_at` | TIMESTAMPTZ | Not null, default now() | Upload timestamp |
| `updated_at` | TIMESTAMPTZ | Not null, default now() | Last status change timestamp |

---

## Acceptance Criteria

- [ ] **Given** a pharmacy_owner uploads a PDF Drug Licence file with `expiry_date=2027-06-30`, **when** POST `/api/v1/pharmacy/kyc/documents` is called, **then** the document is saved with `status=UPLOADED`, a signed URL with 1-hour expiry is returned, and expiry alert notifications are scheduled for T-60 and T-30 days.
- [ ] **Given** a file larger than 10 MB, **when** upload is attempted, **then** HTTP 400 `FILE_TOO_LARGE` is returned and no file is stored.
- [ ] **Given** an UPLOADED document of type `GSTIN_CERTIFICATE` already exists, **when** the pharmacy attempts to upload another `GSTIN_CERTIFICATE`, **then** HTTP 409 `DOCUMENT_TYPE_ALREADY_PENDING` is returned.
- [ ] **Given** all 5 required documents are uploaded, **when** POST `/api/v1/pharmacy/kyc/submit` is called, **then** pharmacy `status` changes to `KYC_SUBMITTED`, admin is notified via in-app notification, and `auto_kyc_triggered` reflects the feature flag state.
- [ ] **Given** a document with `status=REJECTED`, **when** DELETE `/api/v1/pharmacy/kyc/documents/:document_id` is called by the pharmacy_owner, **then** the document record is soft-deleted and the pharmacy can upload a new document of the same type.
- [ ] **Given** a document with `status=VERIFIED`, **when** DELETE is attempted by the pharmacy_owner, **then** HTTP 403 `CANNOT_DELETE_VERIFIED` is returned.
- [ ] **Given** an admin calls POST `/api/v1/admin/pharmacies/:id/kyc/documents/:doc_id/verify` with `verified=false` and a rejection reason, **then** the document `status` changes to `REJECTED`, the rejection reason is stored, and the admin's ID and timestamp are recorded in `verified_by` and `verified_at`.
- [ ] **Given** an admin accesses a signed document URL, **when** the URL is fetched, **then** access is logged in the audit trail with admin_id, document_id, and timestamp.

---

## Dependencies

- STORY-003-001 - Pharmacy registration (pharmacy must exist with PENDING_KYC status)
- STORY-003-003 - Auto KYC Verification (triggered by `/kyc/submit`)
- STORY-003-004 - KYC Status Management Admin (admin verification actions)
- EPIC-001 / STORY-003 - File upload service / virus scanner integration
- EPIC-002 / STORY-001 - Notification service for submission alerts and expiry reminders
- Infrastructure - S3-compatible private bucket; pre-signed URL generation; CDN not used for KYC docs

---

## Notes

- File storage keys are structured as `kyc/{pharmacy_id}/{document_type}/{uuid}.{ext}` to enable easy listing per pharmacy.
- Signed URLs should use the cloud provider's native pre-signing (AWS S3 `GetObject` presign or equivalent). Never cache signed URLs server-side; always generate fresh on each API call.
- Virus scanning: integrate ClamAV or a cloud-native AV scan (e.g., AWS GuardDuty Malware Protection) in the upload pipeline. File is quarantined and rejected if a threat is detected.
- Drug Licence expiry alert at T-60 days uses WhatsApp template `DRUG_LICENCE_EXPIRY_REMINDER_60` and email template `drug_licence_expiry_60`. At T-30 days, escalation is sent with `URGENT` priority flag.
- When all documents for a pharmacy reach `VERIFIED` status, the system should not auto-activate the pharmacy - that remains an explicit admin action (STORY-003-004).
- `PROPRIETOR_ID` accepts Aadhaar card, Voter ID, Driving Licence, or Passport scans.
