# STORY-001: Prescription Upload and Storage

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-001 |
| **Epic** | EPIC-008 - Prescription Management |
| **Priority** | P0 |
| **Complexity** | M |
| **Status** | Draft |

---

## Overview

This story covers the complete lifecycle of a customer's prescription on Namma MedMate - from initial file upload through attachment to a cart and eventual dispense. Customers may upload paper prescriptions (scanned/photographed as PDF, JPG, or PNG) or receive digital e-prescriptions automatically from the teleconsult flow. All prescription files are stored privately in S3 with time-limited signed URLs, and access is strictly scoped so that a prescription is only shared with the pharmacy that actually fulfils the associated order. The story also manages prescription expiry logic (6 months for uploaded, 90 days for e-prescriptions) and provides full CRUD for the customer's prescription wallet.

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| `customer` | Read/Write own | Upload, list, view detail, delete, attach to cart |
| `pharmacy_owner` | Read (order-scoped) | View prescription linked to a specific order only |
| `pharmacy_staff` | Read (order-scoped) | Same as pharmacy_owner |
| `admin_compliance` | Read (audit) | View for compliance audit; cannot delete |
| `admin_support` | Read (support) | View for dispute resolution; cannot delete |

---

## Business Rules

1. **File validation:** Only PDF, JPG, and PNG files are accepted. Maximum file size is 10 MB. Any other MIME type or oversized file is rejected with `INVALID_FILE_FORMAT` or `FILE_TOO_LARGE` respectively.
2. **Private storage with signed URLs:** Prescription files are stored in a private S3 bucket (no public access). File URLs returned to clients are pre-signed with a 1-hour expiry. Each API call regenerates a fresh signed URL; the underlying S3 key never changes.
3. **Prescription expiry:** Uploaded prescriptions (type `UPLOADED`) expire 6 months after `created_at` if not used. e-Prescriptions (type `E_PRESCRIPTION`) expire 90 days after `issued_at`. Expired prescriptions have status `EXPIRED` and cannot be attached to a new cart.
4. **Privacy and data isolation:** A customer can only view, download, or delete their own prescriptions. A pharmacy can only access the prescription linked to an order placed with that pharmacy - raw prescription content is never broadcast. Admin roles access prescriptions only through the audit interface with all access logged.
5. **Deletion constraint:** A prescription that is linked to an active or dispensed order (`associated_order_id` is set and order status is not `CANCELLED`) cannot be deleted. Attempting deletion returns `PRESCRIPTION_IN_USE`.
6. **Cart attachment and Rx validation trigger:** Attaching a prescription to a cart (`POST /api/v1/prescriptions/:id/use-in-cart`) sets `cart.prescription_id`. At checkout, the order placement flow validates that at least one Rx-only item's requirement is covered by the attached prescription. If validation fails, checkout is blocked with `PRESCRIPTION_REQUIRED`.
7. **OCR metadata:** On upload, an asynchronous OCR job attempts to extract `doctor_name`, `prescription_date`, and `medicines_extracted`. Results are stored in the prescription record and do not block the upload response. OCR failure sets extracted fields to `null` and does not affect prescription usability.
8. **Prescription ID logging:** Every order created with a prescription attached records the `prescription_id` in the order record. This creates an auditable link for compliance and the Schedule H1/X drug register.

---

## API Endpoints

### 1. Upload Prescription

```POST /api/v1/prescriptions```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 10 req/min per customer

**Request:** `multipart/form-data`

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `file` | File | Yes | PDF / JPG / PNG, max 10 MB |
| `patient_name` | string | No | Override patient name (defaults to account name) |
| `notes` | string | No | Notes visible to reviewing pharmacy (max 500 chars) |

**Response `201 Created`:**
```json
{
  "success": true,
  "data": {
    "id": "rx_01J3KP7VXYZ123",
    "type": "UPLOADED",
    "status": "UPLOADED",
    "file_url": "https://s3.ap-south-1.amazonaws.com/namma-medmate-rx/private/...?X-Amz-Expires=3600&...",
    "patient_name": "Ravi Kumar",
    "notes": "Morning medicines refill",
    "doctor_name": null,
    "prescription_date": null,
    "medicines_extracted": null,
    "source": "UPLOAD",
    "expires_at": "2027-01-24T07:30:00Z",
    "uploaded_at": "2026-07-24T07:30:00Z",
    "created_at": "2026-07-24T07:30:00Z"
  }
}
```

**Errors:**

| Code | HTTP | Description |
|------|------|-------------|
| `INVALID_FILE_FORMAT` | 422 | File is not PDF/JPG/PNG |
| `FILE_TOO_LARGE` | 422 | File exceeds 10 MB |
| `UPLOAD_FAILED` | 500 | S3 upload error |

---

### 2. List Customer Prescriptions

```GET /api/v1/prescriptions```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 30 req/min

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `status` | string | all | Filter: `UPLOADED`, `PENDING_VERIFICATION`, `VERIFIED`, `REJECTED`, `DISPENSED`, `EXPIRED`, `ALL` |
| `type` | string | all | `UPLOADED` or `E_PRESCRIPTION` |
| `page` | integer | 1 | Pagination page |
| `limit` | integer | 20 | Items per page |
| `sort` | string | `created_at` | Sort field |
| `order` | string | `desc` | `asc` or `desc` |

**Response `200 OK`:**
```json
{
  "success": true,
  "data": [
    {
      "id": "rx_01J3KP7VXYZ123",
      "type": "UPLOADED",
      "status": "VERIFIED",
      "file_url": "https://s3.../signed-url",
      "patient_name": "Ravi Kumar",
      "doctor_name": "Dr. Priya Sharma",
      "prescription_date": "2026-07-20",
      "source": "UPLOAD",
      "expires_at": "2027-01-24T07:30:00Z",
      "associated_order_id": null,
      "created_at": "2026-07-24T07:30:00Z"
    },
    {
      "id": "rx_01J3KP7VABC456",
      "type": "E_PRESCRIPTION",
      "status": "DISPENSED",
      "file_url": "https://s3.../signed-url",
      "patient_name": "Ravi Kumar",
      "doctor_name": "Dr. Anil Mehta",
      "prescription_date": "2026-07-22",
      "source": "TELECONSULT",
      "expires_at": "2026-10-20T11:00:00Z",
      "associated_order_id": "ord_01J3KP7VDEF789",
      "created_at": "2026-07-22T11:00:00Z"
    }
  ],
  "meta": {
    "page": 1,
    "limit": 20,
    "total": 2,
    "total_pages": 1
  }
}
```

---

### 3. Get Prescription Detail

```GET /api/v1/prescriptions/:id```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 60 req/min

**Path Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `id` | UUID | Prescription ID |

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "id": "rx_01J3KP7VXYZ123",
    "type": "UPLOADED",
    "status": "VERIFIED",
    "file_url": "https://s3.../signed-url-fresh",
    "patient_name": "Ravi Kumar",
    "notes": "Morning medicines refill",
    "doctor_name": "Dr. Priya Sharma",
    "prescription_date": "2026-07-20",
    "source": "UPLOAD",
    "medicines_extracted": [
      { "name": "Metformin 500mg", "quantity": "60 tablets", "dosage": "1-0-1" },
      { "name": "Atorvastatin 10mg", "quantity": "30 tablets", "dosage": "0-0-1" }
    ],
    "associated_order_id": null,
    "associated_orders": [],
    "expires_at": "2027-01-24T07:30:00Z",
    "uploaded_at": "2026-07-24T07:30:00Z",
    "created_at": "2026-07-24T07:30:00Z",
    "updated_at": "2026-07-24T08:45:00Z"
  }
}
```

**Errors:**

| Code | HTTP | Description |
|------|------|-------------|
| `PRESCRIPTION_NOT_FOUND` | 404 | ID does not exist or belongs to another customer |

---

### 4. Delete Prescription

```DELETE /api/v1/prescriptions/:id```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 10 req/min

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "message": "Prescription deleted successfully"
  }
}
```

**Errors:**

| Code | HTTP | Description |
|------|------|-------------|
| `PRESCRIPTION_NOT_FOUND` | 404 | ID not found for this customer |
| `PRESCRIPTION_IN_USE` | 409 | Prescription is linked to an active or dispensed order |
| `CANNOT_DELETE_EPRESCRIPTION` | 403 | e-Prescriptions cannot be deleted (permanent medical record) |

---

### 5. Attach Prescription to Cart

```POST /api/v1/prescriptions/:id/use-in-cart```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 20 req/min

**Request Body:**
```json
{
  "cart_id": "cart_01J3KP7VGHJ000"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `cart_id` | UUID | Yes | Active cart to attach prescription to |

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "cart_id": "cart_01J3KP7VGHJ000",
    "prescription_id": "rx_01J3KP7VXYZ123",
    "prescription_status": "VERIFIED",
    "message": "Prescription attached to cart"
  }
}
```

**Errors:**

| Code | HTTP | Description |
|------|------|-------------|
| `PRESCRIPTION_NOT_FOUND` | 404 | Prescription not found for this customer |
| `PRESCRIPTION_EXPIRED` | 422 | Prescription has passed its expiry date |
| `PRESCRIPTION_REJECTED` | 422 | Prescription was rejected by pharmacist |
| `CART_NOT_FOUND` | 404 | Cart ID does not exist or is not ACTIVE |
| `CART_PRESCRIPTION_MISMATCH` | 422 | Cart already has a different prescription attached |

---

## Data Models

### Prescription

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID v4 | PK, NOT NULL | Unique prescription identifier |
| `customer_id` | UUID | FK ? customers, NOT NULL | Owning customer |
| `type` | ENUM | `UPLOADED`, `E_PRESCRIPTION` | Source type |
| `status` | ENUM | NOT NULL | `UPLOADED`, `PENDING_VERIFICATION`, `VERIFIED`, `REJECTED`, `DISPENSED`, `EXPIRED` |
| `s3_key` | string | NOT NULL, UNIQUE | Private S3 object key (never exposed directly) |
| `file_url` | string | virtual (generated) | Time-limited signed URL regenerated on each read |
| `file_size_bytes` | integer | NOT NULL | Original file size |
| `mime_type` | ENUM | NOT NULL | `application/pdf`, `image/jpeg`, `image/png` |
| `patient_name` | string | max 200 chars | Patient name (from upload or account) |
| `notes` | string | max 500 chars, nullable | Customer notes for pharmacy |
| `doctor_name` | string | max 200 chars, nullable | Extracted or e-Rx doctor name |
| `prescription_date` | date | nullable | Date on prescription (extracted or issued) |
| `source` | ENUM | NOT NULL | `UPLOAD`, `TELECONSULT` |
| `medicines_extracted` | JSONB | nullable | OCR-extracted medicines list |
| `associated_order_id` | UUID | FK ? orders, nullable | Order where this Rx was dispensed |
| `teleconsult_id` | UUID | FK ? teleconsults, nullable | Source teleconsult (for E_PRESCRIPTION) |
| `expires_at` | timestamp | NOT NULL | 6 months (UPLOADED) or 90 days (E_PRESCRIPTION) from creation |
| `rejection_reason` | string | nullable | Reason if status = REJECTED |
| `created_at` | timestamp | NOT NULL | Upload or e-Rx issuance time |
| `updated_at` | timestamp | NOT NULL | Last status change time |

---

## Acceptance Criteria

- [ ] **Given** a customer uploads a valid JPG file under 10 MB, **when** the upload completes, **then** the prescription is created with `status: UPLOADED`, a private S3 key is stored, and a 1-hour signed URL is returned.
- [ ] **Given** a customer uploads a file exceeding 10 MB, **when** the upload is attempted, **then** the API returns HTTP 422 with `FILE_TOO_LARGE`.
- [ ] **Given** a customer uploads a `.docx` file, **when** the upload is attempted, **then** the API returns HTTP 422 with `INVALID_FILE_FORMAT`.
- [ ] **Given** a customer tries to delete a prescription linked to an order in `PACKING` status, **when** the delete is attempted, **then** the API returns HTTP 409 with `PRESCRIPTION_IN_USE`.
- [ ] **Given** a prescription has `type: UPLOADED` and was created 183 days ago, **when** the expiry job runs, **then** the prescription status is updated to `EXPIRED` and cannot be attached to a cart.
- [ ] **Given** a customer attaches an `EXPIRED` prescription to their cart, **when** the attachment is attempted, **then** the API returns HTTP 422 with `PRESCRIPTION_EXPIRED`.
- [ ] **Given** a prescription is attached to a cart, **when** the cart is viewed by another customer (different `customer_id`), **then** the API returns HTTP 404 (not 403) to prevent enumeration.
- [ ] **Given** an uploaded prescription, **when** the OCR job completes, **then** `medicines_extracted`, `doctor_name`, and `prescription_date` are populated in the record without re-running the upload flow.
- [ ] **Given** a customer calls `GET /api/v1/prescriptions/:id` for the same prescription twice within 2 hours, **then** two different signed URLs are returned (each freshly generated, both valid for 1 hour).
- [ ] **Given** an e-prescription from teleconsult, **when** a customer attempts to delete it, **then** the API returns HTTP 403 with `CANNOT_DELETE_EPRESCRIPTION`.

---

## Dependencies

| Dependency | Story / System | Notes |
|------------|---------------|-------|
| S3 private bucket + IAM role for signed URL generation | DevOps / Infrastructure | Bucket name and role ARN required in env config |
| OCR service integration | Platform (async job) | Decoupled via job queue; failure gracefully handled |
| EPIC-009 STORY-004 - e-Prescription generation | Upstream | e-Rx records are created by teleconsult flow and read here |
| EPIC-010 STORY-001 - Cart management | Downstream | `POST /api/v1/prescriptions/:id/use-in-cart` updates cart state |
| EPIC-010 STORY-004 - Order placement | Downstream | Rx validation at checkout references prescription status |
| Auth service (JWT customer role) | EPIC-001 | Standard bearer token auth |

---

## Notes

- Signed URL generation must be server-side only; the S3 key must never be exposed to the client.
- The `file_url` field is **not persisted** in the database; it is generated fresh on every read using the stored `s3_key`.
- Expiry job should run daily at midnight IST (`cron: 0 0 * * *`) and batch-update expired prescriptions.
- For the Rx quote broadcast flow (EPIC-010 STORY-003), the prescription file is transmitted to a pharmacy only after the customer selects that pharmacy's quote, not during broadcast.
- `medicines_extracted` JSONB schema: `[{ "name": string, "quantity": string, "dosage": string, "schedule": string | null }]`.
