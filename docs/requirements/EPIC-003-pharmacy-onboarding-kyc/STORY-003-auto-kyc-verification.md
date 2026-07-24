# STORY-003-003: Auto KYC Verification

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-003-003 |
| **Epic** | EPIC-003 - Pharmacy Onboarding & KYC |
| **Priority** | P0 |
| **Complexity** | L |
| **Status** | Draft |

---

## Overview

This story covers the automated KYC verification subsystem that cross-checks pharmacy compliance identifiers against authoritative government APIs: GSTN for GSTIN validation, state drug control board registries for Drug Licence status and expiry, and the FSSAI portal for food business operator licence verification. Auto-KYC is triggered automatically on document submission (when the `kyc_auto_verification_enabled` feature flag is ON) and can also be manually re-triggered by admin. If all three checks pass, the pharmacy is auto-activated to `ACTIVE` status without requiring manual admin review. If any check fails or errors, the pharmacy is routed to the manual admin KYC queue. All check results are stored and surfaced in the admin view. The subsystem is designed to be resilient: external API failures are treated as `ERROR` (not `FAIL`), logged, and retried with exponential backoff.

---

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| `admin_super` | Trigger, Read | Can manually trigger auto-KYC, view all results |
| `admin_operations` | Trigger, Read | Can trigger auto-KYC for pharmacies in their queue |
| `admin_compliance` | Read | Can view auto-KYC results and audit logs |
| `pharmacy_owner` | Read (own) | Can see aggregate pass/fail result but not raw API responses |
| System/Rules Engine | Trigger | Auto-triggers on KYC submission when feature flag is ON |

---

## Business Rules

1. **Feature flag gating**: Auto-KYC only runs when the platform configuration key `kyc_auto_verification_enabled` is `true`. If the flag is `false`, all pharmacies go directly to the manual admin queue after document submission.
2. **GSTIN check is synchronous**: The GSTN API call is made inline with the auto-verify trigger. Maximum timeout is 10 seconds. If the API does not respond within 10 seconds, status is set to `ERROR` and the check is retried asynchronously up to 3 times with exponential backoff (10s, 30s, 90s).
3. **Drug Licence and FSSAI checks are asynchronous**: These checks are dispatched to a background job queue (e.g., Bull/Redis). Results may arrive via webhook callback from the API provider or by polling. Maximum async window is 30 minutes; if no result within 30 minutes, status is set to `ERROR`.
4. **Auto-activation condition**: Pharmacy is auto-activated to `ACTIVE` status only if ALL three checks (`GSTIN`, `DRUG_LICENCE`, `FSSAI`) return `PASS`. Auto-activation also sets `is_online=true`, assigns the pharmacy to a zone (based on pincode-zone mapping table), sends a welcome notification (WhatsApp + email), and initialises the FREE plan.
5. **Routing to manual queue**: If ANY check returns `FAIL` or `ERROR`, the pharmacy status remains `KYC_SUBMITTED` and an admin task is created in the KYC review queue with the auto-verify result attached for context.
6. **All verification calls are audit-logged**: Every API request and response (including headers and body, with PII redacted) is stored in the `KycVerification` table. Request payloads with secrets (API keys) are never logged; only the structured request parameters are stored.
7. **Auto-KYC does not replace manual review for edge cases**: Even if auto-KYC passes, an admin can still override by manually rejecting a pharmacy (STORY-003-004). Auto-activation is a convenience flow, not a compliance bypass.
8. **Retry logic on transient errors**: HTTP 5xx responses, timeouts, and network errors from external APIs are treated as transient and retried. HTTP 4xx client errors (e.g., invalid identifier format) are treated as non-retryable failures and recorded as `FAIL`.
9. **GSTIN business name cross-check**: If the GSTN API returns a business name that differs significantly (Levenshtein distance > 5 tokens) from the `business_name` on the Pharmacy record, the GSTIN check is flagged as `WARN` (not auto-failed) and a flag is added to the manual review task for admin attention.
10. **Drug Licence expiry enforcement**: If the Drug Licence API returns an expiry date earlier than 90 days from today, the check result is `FAIL` even if the licence is technically active, and the pharmacy must renew before activation.

---

## API Endpoints

### 1. Admin - Trigger Auto-KYC Verification

```
POST /api/v1/admin/pharmacies/:id/kyc/auto-verify
```

**Authentication:** Bearer JWT - `admin_super`, `admin_operations`
**Rate Limit:** 10 req/min per admin

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| `id` | UUID | Pharmacy ID to run auto-KYC for |

**Request Body (application/json):**
```json
{
  "checks": ["string - optional, array of check types to run: GSTIN | DRUG_LICENCE | FSSAI; defaults to all three if omitted"]
}
```

**Success Response - 202 Accepted:**
```json
{
  "success": true,
  "data": {
    "pharmacy_id": "uuid-v4",
    "job_id": "uuid-v4",
    "checks_triggered": ["GSTIN", "DRUG_LICENCE", "FSSAI"],
    "gstin_result": {
      "status": "PASS",
      "gstin": "27AABCS1429B1ZB",
      "business_name_api": "Sharma Medical Stores",
      "registration_status": "ACTIVE",
      "filing_status": "REGULAR",
      "checked_at": "2026-07-24T00:00:00Z"
    },
    "drug_licence_result": {
      "status": "PENDING",
      "message": "Async check dispatched. Poll auto-verify-result for status."
    },
    "fssai_result": {
      "status": "PENDING",
      "message": "Async check dispatched. Poll auto-verify-result for status."
    },
    "estimated_completion_minutes": 10
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `INVALID_CHECK_TYPE` | Unknown check type in `checks` array |
| 404 | `PHARMACY_NOT_FOUND` | Pharmacy ID does not exist |
| 409 | `KYC_NOT_SUBMITTED` | Pharmacy has not submitted KYC documents yet |
| 409 | `AUTO_KYC_IN_PROGRESS` | A previous auto-KYC job is still running for this pharmacy |
| 503 | `FEATURE_FLAG_DISABLED` | `kyc_auto_verification_enabled` is false |

---

### 2. Admin - Get Auto-KYC Verification Results

```
GET /api/v1/admin/pharmacies/:id/kyc/auto-verify-result
```

**Authentication:** Bearer JWT - `admin_super`, `admin_operations`, `admin_compliance`
**Rate Limit:** 60 req/min

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| `id` | UUID | Pharmacy ID |

**Query Parameters:**
| Param | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `job_id` | UUID | No | latest | Specific auto-KYC job ID; defaults to most recent |

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "pharmacy_id": "uuid-v4",
    "job_id": "uuid-v4",
    "overall_status": "PARTIAL",
    "auto_activated": false,
    "triggered_at": "2026-07-24T00:00:00Z",
    "completed_at": null,
    "checks": [
      {
        "verification_id": "uuid-v4",
        "verification_type": "GSTIN",
        "api_provider": "GSTN_SANDBOX_API",
        "status": "PASS",
        "details": {
          "gstin": "27AABCS1429B1ZB",
          "business_name_registered": "SHARMA MEDICAL STORES",
          "business_name_platform": "Sharma Medical Store",
          "name_match": "WARN",
          "registration_status": "ACTIVE",
          "gstin_type": "Regular",
          "filing_status": "Filed",
          "state_code": "27"
        },
        "checked_at": "2026-07-24T00:00:05Z",
        "retry_count": 0
      },
      {
        "verification_id": "uuid-v4",
        "verification_type": "DRUG_LICENCE",
        "api_provider": "MH_DRUG_CONTROL_API",
        "status": "PENDING",
        "details": null,
        "checked_at": null,
        "retry_count": 0
      },
      {
        "verification_id": "uuid-v4",
        "verification_type": "FSSAI",
        "api_provider": "FSSAI_PORTAL_API",
        "status": "PASS",
        "details": {
          "licence_number": "11223344556677",
          "business_name": "SHARMA MEDICAL STORES",
          "licence_status": "ACTIVE",
          "expiry_date": "2028-03-31",
          "category": "Retail"
        },
        "checked_at": "2026-07-24T00:01:30Z",
        "retry_count": 1
      }
    ],
    "admin_flags": [
      {
        "flag": "BUSINESS_NAME_MISMATCH",
        "detail": "GSTIN-registered name 'SHARMA MEDICAL STORES' differs from platform name 'Sharma Medical Store'. Please verify manually.",
        "severity": "WARN"
      }
    ]
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 404 | `PHARMACY_NOT_FOUND` | Pharmacy ID does not exist |
| 404 | `NO_AUTO_KYC_JOB` | No auto-KYC job has been run for this pharmacy |

---

### 3. Internal - System Webhook Receiver (Drug Licence / FSSAI Callback)

```
POST /api/v1/internal/kyc/webhook-callback
```

**Authentication:** HMAC-SHA256 signature in `X-Webhook-Signature` header (shared secret per API provider)
**Rate Limit:** 100 req/min per provider IP

**Request Body (application/json):**
```json
{
  "provider": "string - MH_DRUG_CONTROL_API | FSSAI_PORTAL_API",
  "job_id": "string - UUID of the auto-KYC job",
  "verification_type": "string - DRUG_LICENCE | FSSAI",
  "status": "string - PASS | FAIL | ERROR",
  "data": {
    "licence_number": "string",
    "registered_name": "string",
    "licence_status": "string - ACTIVE | EXPIRED | CANCELLED | SUSPENDED",
    "expiry_date": "string - YYYY-MM-DD or null",
    "state": "string",
    "error_code": "string - populated if status=ERROR"
  }
}
```

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "acknowledged": true,
    "verification_id": "uuid-v4"
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 401 | `INVALID_WEBHOOK_SIGNATURE` | HMAC signature does not match |
| 404 | `JOB_NOT_FOUND` | `job_id` not found or already completed |

---

## Data Models

### KycVerification

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID v4 | PK | Unique verification record |
| `pharmacy_id` | UUID | FK ? Pharmacy.id, not null | Owning pharmacy |
| `job_id` | UUID | Not null, indexed | Groups all checks in a single auto-KYC run |
| `verification_type` | ENUM | Not null | GSTIN \| DRUG_LICENCE \| FSSAI |
| `api_provider` | VARCHAR(100) | Not null | e.g. GSTN_SANDBOX_API, MH_DRUG_CONTROL_API, FSSAI_PORTAL_API |
| `request_payload` | JSONB | Not null | Sanitised request sent to the external API |
| `response_payload` | JSONB | Nullable | Raw response received (PII fields redacted) |
| `status` | ENUM | Not null, default PENDING | PASS \| FAIL \| PENDING \| ERROR \| WARN |
| `details` | JSONB | Nullable | Structured result details (business name, licence status, expiry, etc.) |
| `admin_flags` | JSONB | Nullable, default [] | Array of flag objects `{ flag, detail, severity }` |
| `retry_count` | SMALLINT | Not null, default 0 | Number of retries attempted |
| `verified_at` | TIMESTAMPTZ | Nullable | Timestamp of final status determination |
| `created_at` | TIMESTAMPTZ | Not null, default now() | Job dispatch timestamp |

### AutoKycJob

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID v4 | PK | Job identifier |
| `pharmacy_id` | UUID | FK ? Pharmacy.id, not null | Pharmacy being verified |
| `triggered_by` | UUID | FK ? User.id, nullable | Admin who triggered; null if system-triggered |
| `trigger_source` | ENUM | Not null | SYSTEM \| ADMIN |
| `overall_status` | ENUM | Not null, default PENDING | PENDING \| PARTIAL \| PASS \| FAIL \| ERROR |
| `auto_activated` | BOOLEAN | Not null, default false | Whether pharmacy was auto-activated by this job |
| `triggered_at` | TIMESTAMPTZ | Not null, default now() | When the job was created |
| `completed_at` | TIMESTAMPTZ | Nullable | When all checks resolved |

---

## Acceptance Criteria

- [ ] **Given** `kyc_auto_verification_enabled=true` and a pharmacy submits KYC, **when** `/kyc/submit` is called, **then** an `AutoKycJob` is created and GSTIN check runs synchronously (within 10s), while Drug Licence and FSSAI checks are dispatched to the async queue.
- [ ] **Given** all three checks return `PASS` within the async window, **when** the final check resolves, **then** pharmacy `status` changes to `ACTIVE`, `is_online` is set to `true`, zone is assigned from pincode mapping, and a welcome notification is sent via WhatsApp and email.
- [ ] **Given** FSSAI check returns `FAIL`, **when** the result is recorded, **then** pharmacy remains `KYC_SUBMITTED`, an admin KYC task is created in the review queue, and the auto-verify result with the FAIL detail is attached to that task.
- [ ] **Given** the GSTN API returns a business name with Levenshtein distance > 5 tokens from the platform `business_name`, **when** GSTIN check resolves, **then** check status is `PASS` but an admin flag `BUSINESS_NAME_MISMATCH` with `severity=WARN` is added.
- [ ] **Given** the Drug Licence API returns an expiry date < 90 days from today, **when** the result is received, **then** Drug Licence check status is `FAIL` with reason `LICENCE_EXPIRING_SOON`.
- [ ] **Given** the external Drug Licence API returns HTTP 500, **when** the error is received, **then** the check status is set to `ERROR`, retried up to 3 times with exponential backoff (10s, 30s, 90s), and after all retries exhausted, the job routes to the manual admin queue.
- [ ] **Given** an admin calls GET `/api/v1/admin/pharmacies/:id/kyc/auto-verify-result`, **then** all `KycVerification` records for the latest job are returned including status, details, and any admin flags, with no raw API secrets in the response.
- [ ] **Given** `kyc_auto_verification_enabled=false`, **when** an admin calls POST `/api/v1/admin/pharmacies/:id/kyc/auto-verify`, **then** HTTP 503 `FEATURE_FLAG_DISABLED` is returned.

---

## Dependencies

- STORY-003-002 - KYC document submission (auto-KYC triggered post-submission)
- STORY-003-004 - KYC Status Management (manual queue when auto-KYC fails)
- EPIC-001 / STORY-004 - Feature flag service (`kyc_auto_verification_enabled`)
- EPIC-002 / STORY-001 - Notification service (welcome notification on auto-activation)
- EPIC-009 / STORY-001 - Zone assignment (pincode ? zone mapping used during auto-activation)
- External: GSTN API - GSTIN verification endpoint
- External: State Drug Control APIs - Drug licence status (state-specific endpoints, adapter pattern)
- External: FSSAI Portal API - FSSAI licence validation
- Infrastructure: Bull/Redis job queue for async checks

---

## Notes

- An adapter pattern is strongly recommended for external KYC APIs: each provider (GSTN, state drug control boards, FSSAI) has a dedicated adapter class that normalises request/response to a common `KycCheckResult` interface. This allows swapping providers or adding new states without changing core business logic.
- State drug control APIs vary by Indian state. Priority states for initial launch: Maharashtra (MH), Karnataka (KA), Tamil Nadu (TN), Delhi (DL). Other states fall back to manual review.
- GSTN API sandbox is available for development and staging environments. Production requires registration with NIC (National Informatics Centre).
- Webhook callback HMAC secret is stored in Vault/SSM Parameter Store, never in application config files.
- If auto-KYC completes in < 5 minutes for all checks, the pharmacy owner should receive a real-time push notification (WebSocket or Firebase) with the result.
- The `request_payload` field should redact any API authentication tokens before storage; use a `sanitise_request` helper.
