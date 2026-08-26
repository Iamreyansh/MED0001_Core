# STORY-003-004: KYC Status Management (Admin)

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-003-004 |
| **Epic** | EPIC-003 - Pharmacy Onboarding & KYC |
| **Priority** | P0 |
| **Complexity** | M |
| **Status** | Draft |

---

## Overview

This story covers the admin-facing workflow for reviewing, approving, rejecting, suspending, reactivating, and communicating with pharmacies at the KYC stage and beyond. Admin operations staff work from a prioritised queue of PENDING_KYC and KYC_SUBMITTED pharmacies, view full KYC bundles (documents + auto-verify results), and take decisive actions. Approval activates the pharmacy on the marketplace. Rejection communicates reasons and optionally blocks reapplication. Suspension removes an active pharmacy from the customer app immediately. All decisions are audit-logged. This story also covers the ability to request additional documents from pharmacies as part of the KYC conversation.

---

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| `admin_super` | Full | Approve, reject, suspend, reactivate, request docs, change commission |
| `admin_operations` | Full | Approve, reject, suspend, reactivate, request docs |
| `admin_compliance` | Read, Request docs | View KYC details; can request additional documents; cannot approve/reject |
| `admin_support` | Read | View pharmacy status and basic details only |
| `pharmacy_owner` | Receive notifications | Receives status change notifications; no direct access to admin endpoints |

---

## Business Rules

1. **Only `admin_super` or `admin_operations` can approve or reject KYC**: Other admin roles (finance, compliance, support) are read-only for KYC decisions. Attempting to approve/reject from an unauthorised role returns HTTP 403.
2. **Approval sets commission and zone**: The `POST /approve` endpoint requires `commission_pct` (range 3-20%) and `zone_id`. If not provided, defaults from platform config are applied. Approval sets `status=ACTIVE`, `is_online=true`, assigns `zone_id`, and sets `commission_pct` on the Pharmacy record.
3. **Suspension immediately hides pharmacy**: On suspension, `is_online=false` is set and the pharmacy is excluded from all customer-app queries within seconds (cache invalidated). Active orders that are already placed continue through their lifecycle. No new orders can be placed.
4. **Suspension reason is communicated**: A WhatsApp message (approved template) and email are sent to the pharmacy owner within 1 minute of suspension action, including the reason and next steps.
5. **Permanent suspension blocks reapplication**: `suspend_type=PERMANENT` sets `can_reapply=false` on the Pharmacy record. Temporary suspensions set `can_reapply=true` by default.
6. **KYC rejection with `can_reapply=false` is irreversible by pharmacy**: Only `admin_super` can set `can_reapply=true` again on a Pharmacy record after it has been set to false.
7. **All KYC decisions are audit-logged**: Every approve, reject, suspend, reactivate action is written to `AuditLog` with `action`, `actor_id` (admin user), `pharmacy_id`, `payload` (reason, commission, zone), and `timestamp`. Audit logs are immutable (append-only).
8. **Commission change requires `admin_finance` role**: The `PATCH /commission` endpoint (STORY-004-003) is separate from approval. Changing commission post-activation requires `admin_finance` role. The approve endpoint sets the initial commission.
9. **Document request pauses the KYC clock**: Calling `/request-documents` resets the admin SLA timer for that pharmacy; the KYC review SLA of 24 business hours restarts when the pharmacy re-submits.
10. **Reactivation requires explicit notes**: The `POST /reactivate` endpoint requires a non-empty `notes` field explaining why the pharmacy is being reactivated. This is stored in the audit log.

---

## API Endpoints

### 1. List Pharmacies Pending KYC

```
GET /api/v1/admin/pharmacies
```

**Authentication:** Bearer JWT - `admin_super`, `admin_operations`, `admin_compliance`, `admin_support`
**Rate Limit:** 60 req/min

**Query Parameters:**
| Param | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `status` | string | No | ALL | Filter: PENDING_KYC \| KYC_SUBMITTED \| ACTIVE \| SUSPENDED \| REJECTED \| ALL |
| `zone_id` | UUID | No | - | Filter by assigned zone |
| `plan` | string | No | - | Filter: FREE \| STARTER \| GROWTH \| PRO |
| `is_online` | boolean | No | - | Filter by online status |
| `search` | string | No | - | Fuzzy search on name, owner, phone, pharmacy code |
| `sort` | string | No | `created_at` | Sort field: created_at \| submitted_at \| business_name |
| `order` | string | No | `desc` | asc \| desc |
| `page` | integer | No | 1 | Page number |
| `limit` | integer | No | 50 | Records per page, max 200 |

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "pharmacies": [
      {
        "pharmacy_id": "uuid-v4",
        "code": "PHM-0042",
        "business_name": "Sharma Medical Store",
        "owner_name": "Rajesh Sharma",
        "phone": "+919876543210",
        "zone": "Koramangala Zone",
        "status": "KYC_SUBMITTED",
        "plan": "FREE",
        "is_online": false,
        "submitted_at": "2026-07-23T10:00:00Z",
        "document_age_hours": 14,
        "auto_kyc_status": "PARTIAL",
        "urgency": "HIGH",
        "created_at": "2026-07-22T08:00:00Z"
      }
    ]
  },
  "meta": {
    "page": 1,
    "limit": 50,
    "total": 128,
    "has_next": true
  }
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 403 | `FORBIDDEN` | Caller is not an admin role |

---

### 2. Get Full Pharmacy Detail (Admin)

```
GET /api/v1/admin/pharmacies/:id
```

**Authentication:** Bearer JWT - `admin_super`, `admin_operations`, `admin_compliance`, `admin_support`, `admin_finance`
**Rate Limit:** 60 req/min

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
    "code": "PHM-0042",
    "business_name": "Sharma Medical Store",
    "owner_name": "Rajesh Sharma",
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
    "gstin": "29AABCS1429B1ZB",
    "drug_licence_number": "KA/DL/2024/12345",
    "fssai_number": "11223344556677",
    "pan_number": "AABCS1429B",
    "status": "KYC_SUBMITTED",
    "plan": "FREE",
    "commission_pct": 8.00,
    "zone_id": null,
    "is_online": false,
    "can_reapply": true,
    "kyc": {
      "submitted_at": "2026-07-23T10:00:00Z",
      "auto_kyc_status": "PARTIAL",
      "documents_summary": {
        "GSTIN_CERTIFICATE": "VERIFIED",
        "DRUG_LICENCE": "UNDER_REVIEW",
        "FSSAI_CERTIFICATE": "VERIFIED",
        "PAN_CARD": "REJECTED",
        "BANK_STATEMENT": "UPLOADED"
      }
    },
    "performance": null,
    "created_at": "2026-07-22T08:00:00Z"
  },
  "meta": {}
}
```

---

### 3. Approve KYC and Activate Pharmacy

```
POST /api/v1/admin/pharmacies/:id/approve
```

**Authentication:** Bearer JWT - `admin_super`, `admin_operations`
**Rate Limit:** 30 req/min

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| `id` | UUID | Pharmacy ID |

**Request Body (application/json):**
```json
{
  "commission_pct": "number - required, range 3.00-20.00, two decimal places",
  "zone_id": "string (UUID) - required, must be a valid active zone ID",
  "notes": "string - optional, internal admin notes, max 500 chars"
}
```

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "pharmacy_id": "uuid-v4",
    "status": "ACTIVE",
    "is_online": true,
    "commission_pct": 8.00,
    "zone_id": "zone-uuid-v4",
    "activated_at": "2026-07-24T00:10:00Z",
    "notifications_sent": ["WHATSAPP", "EMAIL"],
    "message": "Pharmacy approved and activated successfully."
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `INVALID_COMMISSION_PCT` | commission_pct outside 3-20 range |
| 400 | `INVALID_ZONE` | zone_id does not refer to an active zone |
| 403 | `FORBIDDEN` | Caller is not admin_super or admin_operations |
| 404 | `PHARMACY_NOT_FOUND` | Pharmacy ID does not exist |
| 409 | `ALREADY_ACTIVE` | Pharmacy is already in ACTIVE status |
| 409 | `KYC_NOT_SUBMITTED` | Pharmacy has not submitted KYC yet |

---

### 4. Reject KYC Application

```
POST /api/v1/admin/pharmacies/:id/reject
```

**Authentication:** Bearer JWT - `admin_super`, `admin_operations`
**Rate Limit:** 30 req/min

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| `id` | UUID | Pharmacy ID |

**Request Body (application/json):**
```json
{
  "rejection_reason": "string - required, max 200 chars, high-level reason shown to pharmacy",
  "rejection_details": "string - optional, max 1000 chars, detailed explanation",
  "can_reapply": "boolean - required; false permanently blocks reapplication"
}
```

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "pharmacy_id": "uuid-v4",
    "status": "REJECTED",
    "can_reapply": true,
    "rejection_reason": "Drug Licence is expired. Please renew and reapply.",
    "rejected_at": "2026-07-24T00:15:00Z",
    "notifications_sent": ["WHATSAPP", "EMAIL"]
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `REJECTION_REASON_REQUIRED` | `rejection_reason` is empty |
| 403 | `FORBIDDEN` | Caller is not admin_super or admin_operations |
| 404 | `PHARMACY_NOT_FOUND` | Pharmacy ID does not exist |
| 409 | `ALREADY_ACTIVE` | Pharmacy already active; use suspend instead |

---

### 5. Suspend Active Pharmacy

```
POST /api/v1/admin/pharmacies/:id/suspend
```

**Authentication:** Bearer JWT - `admin_super`, `admin_operations`
**Rate Limit:** 20 req/min

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| `id` | UUID | Pharmacy ID |

**Request Body (application/json):**
```json
{
  "reason": "string - required, max 500 chars, communicated to pharmacy",
  "suspend_type": "string - required, enum: TEMPORARY | PERMANENT",
  "notes": "string - optional, internal admin notes, max 1000 chars"
}
```

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "pharmacy_id": "uuid-v4",
    "status": "SUSPENDED",
    "is_online": false,
    "suspend_type": "TEMPORARY",
    "can_reapply": true,
    "suspended_at": "2026-07-24T00:20:00Z",
    "notifications_sent": ["WHATSAPP", "EMAIL"]
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `REASON_REQUIRED` | `reason` is empty |
| 403 | `FORBIDDEN` | Caller not admin_super or admin_operations |
| 404 | `PHARMACY_NOT_FOUND` | Pharmacy ID not found |
| 409 | `ALREADY_SUSPENDED` | Pharmacy already in SUSPENDED status |

---

### 6. Reactivate Suspended Pharmacy

```
POST /api/v1/admin/pharmacies/:id/reactivate
```

**Authentication:** Bearer JWT - `admin_super`, `admin_operations`
**Rate Limit:** 20 req/min

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| `id` | UUID | Pharmacy ID |

**Request Body (application/json):**
```json
{
  "notes": "string - required, max 500 chars, reason for reactivation; stored in audit log"
}
```

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "pharmacy_id": "uuid-v4",
    "status": "ACTIVE",
    "is_online": true,
    "reactivated_at": "2026-07-24T00:25:00Z",
    "notifications_sent": ["WHATSAPP", "EMAIL"]
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `NOTES_REQUIRED` | `notes` is empty |
| 403 | `FORBIDDEN` | Caller not admin_super or admin_operations |
| 404 | `PHARMACY_NOT_FOUND` | Pharmacy ID not found |
| 409 | `NOT_SUSPENDED` | Pharmacy is not in SUSPENDED status |

---

### 7. Request Additional Documents from Pharmacy

```
POST /api/v1/admin/pharmacies/:id/request-documents
```

**Authentication:** Bearer JWT - `admin_super`, `admin_operations`, `admin_compliance`
**Rate Limit:** 20 req/min

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| `id` | UUID | Pharmacy ID |

**Request Body (application/json):**
```json
{
  "document_types": ["string - array of document type enums: GSTIN_CERTIFICATE | DRUG_LICENCE | FSSAI_CERTIFICATE | PAN_CARD | BANK_STATEMENT | PROPRIETOR_ID"],
  "message": "string - required, max 1000 chars, human-readable instructions for pharmacy owner"
}
```

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "pharmacy_id": "uuid-v4",
    "requested_document_types": ["PAN_CARD", "BANK_STATEMENT"],
    "message": "Additional documents have been requested. Pharmacy has been notified.",
    "kyc_sla_reset_at": "2026-07-24T00:30:00Z",
    "notifications_sent": ["WHATSAPP", "EMAIL", "IN_APP"]
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `INVALID_DOCUMENT_TYPES` | Unknown document type in array |
| 400 | `MESSAGE_REQUIRED` | `message` is empty |
| 404 | `PHARMACY_NOT_FOUND` | Pharmacy ID not found |

---

## Data Models

### AuditLog

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID v4 | PK | Unique audit entry |
| `entity_type` | VARCHAR(50) | Not null | e.g. PHARMACY, KYC_DOCUMENT, USER |
| `entity_id` | UUID | Not null | ID of the affected entity |
| `action` | VARCHAR(100) | Not null | e.g. KYC_APPROVED, KYC_REJECTED, PHARMACY_SUSPENDED |
| `actor_id` | UUID | FK ? User.id, nullable | Admin user who took the action; null for system actions |
| `actor_role` | VARCHAR(50) | Not null | Role of actor at time of action |
| `payload` | JSONB | Not null, default {} | Action-specific data (reason, commission, zone, etc.) |
| `ip_address` | INET | Nullable | Requesting IP address |
| `created_at` | TIMESTAMPTZ | Not null, default now() | Immutable timestamp |

---

## Acceptance Criteria

- [ ] **Given** a pharmacy in `KYC_SUBMITTED` status, **when** an `admin_operations` user calls POST `/api/v1/admin/pharmacies/:id/approve` with a valid `commission_pct` and `zone_id`, **then** pharmacy `status` changes to `ACTIVE`, `is_online=true`, `commission_pct` and `zone_id` are set, and a WhatsApp + email welcome notification is sent to the pharmacy owner.
- [ ] **Given** an `admin_compliance` user calls POST `/api/v1/admin/pharmacies/:id/approve`, **then** HTTP 403 `FORBIDDEN` is returned and no changes are made.
- [ ] **Given** POST `/api/v1/admin/pharmacies/:id/reject` is called with `can_reapply=false`, **then** pharmacy `status=REJECTED`, `can_reapply=false` is set on the Pharmacy record, and the pharmacy owner receives a rejection notification with the reason.
- [ ] **Given** POST `/api/v1/admin/pharmacies/:id/suspend` with `suspend_type=PERMANENT`, **then** `status=SUSPENDED`, `is_online=false`, `can_reapply=false`, and a WhatsApp + email notification is sent within 1 minute.
- [ ] **Given** a pharmacy in `SUSPENDED` status, **when** POST `/api/v1/admin/pharmacies/:id/reactivate` is called with `notes`, **then** pharmacy `status=ACTIVE`, `is_online=true`, and an audit log entry with `action=PHARMACY_REACTIVATED` is written.
- [ ] **Given** any approve/reject/suspend/reactivate action, **then** an `AuditLog` record is written with the correct `action`, `actor_id`, `actor_role`, `entity_id`, and `payload` including the reason/notes.
- [ ] **Given** GET `/api/v1/admin/pharmacies?status=KYC_SUBMITTED`, **then** only pharmacies in `KYC_SUBMITTED` status are returned, sorted by `submitted_at` ascending (oldest first) by default.
- [ ] **Given** POST `/api/v1/admin/pharmacies/:id/request-documents` with valid `document_types` and `message`, **then** the pharmacy owner receives an in-app + email + WhatsApp notification, and the KYC SLA timer resets.
- [ ] **Given** all KYC documents are verified, **when** no admin has called approve, **then** pharmacy status stays `PENDING_KYC` or `KYC_SUBMITTED` and the system never auto-activates the pharmacy (D8).

---

## Dependencies

- STORY-003-001 - Pharmacy registration (pharmacy records must exist)
- STORY-003-002 - KYC document upload (documents must be submitted before approval)
- Government auto-KYC is out of scope; activation is an explicit admin approve only (D8)
- EPIC-001 / STORY-005 - Role-based access control (admin role enforcement)
- EPIC-002 / STORY-001 - Notification service (WhatsApp templates for approve/reject/suspend)
- EPIC-007 / STORY-001 - Plan initialisation at approval
- EPIC-009 / STORY-001 - Zone management (valid zone IDs for assignment)

---

## Notes

- The pharmacy list endpoint (`GET /api/v1/admin/pharmacies`) is shared with STORY-004-001 (pharmacy directory) but scoped to KYC workflow here. The same endpoint serves both use cases via `status` filter.
- `urgency` field in the list response is computed as: `HIGH` if document_age > 48 hours, `MEDIUM` if 24-48 hours, `LOW` if < 24 hours.
- WhatsApp templates for KYC approved, rejected, and suspended events must be pre-approved by Meta before going live. Template names: `PHARMACY_KYC_APPROVED`, `PHARMACY_KYC_REJECTED`, `PHARMACY_SUSPENDED`.
- Suspension does not cancel/refund paid plan subscriptions; billing continues. The pharmacy owner must contact support to request a billing pause.
- The `code` field (e.g., `PHM-0042`) is a sequential human-readable identifier auto-assigned at registration, formatted as `PHM-{zero-padded 4-digit sequence}`.
