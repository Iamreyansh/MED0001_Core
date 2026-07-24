# STORY-004-003: Commission & Payout Management

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-004-003 |
| **Epic** | EPIC-004 - Pharmacy Operations (Admin View) |
| **Priority** | P0 |
| **Complexity** | L |
| **Status** | Draft |

---

## Overview

This story covers the financial management layer between Namma MedMate and its pharmacy partners: commission configuration, settlement ledger, TCS (Tax Collected at Source) computation under Section 194-O, net payout calculation, and settlement release/hold workflows. Admin finance staff use these endpoints to view per-pharmacy commission structures, track GMV and earnings across settlement periods, adjust commission tiers with documented reasons, and release or hold weekly settlement payouts via RazorpayX. Every financial action is traceable with before/after values in the audit log. Pharmacies receive automated payout receipts via email when a settlement is released.

---

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| `admin_finance` | Full | View, change commission; view, release, hold settlements |
| `admin_super` | Full | All capabilities including commission change |
| `admin_operations` | Read | View commission and settlement status; cannot change or release |
| `admin_support` | Read (limited) | Can view settlement status; cannot see commission details |
| `pharmacy_owner` | Read (own) | Via pharmacy dashboard API - view own settlements and commission |

---

## Business Rules

1. **Commission percentage range**: Commission must be between 3.00% and 20.00% (inclusive). Attempting to set a value outside this range returns `INVALID_COMMISSION_PCT`. Commission is stored with two decimal places (e.g., 8.00%).
2. **Commission changes are effective from a future date**: The `effective_from` field in PATCH `/commission` must be a future date (tomorrow or later). Commission changes cannot be backdated. The change is stored in a `CommissionHistory` record and the `Pharmacy.commission_pct` is updated via a scheduled job at midnight on `effective_from`.
3. **Commission changes are audit-logged with before/after values**: Every commission change creates an `AuditLog` entry with `action=COMMISSION_CHANGED`, `before_value`, `after_value`, `effective_from`, `reason`, and `actor_id`.
4. **TCS computation**: TCS (Tax Collected at Source) = 1% of GMV for all marketplace sales, per Section 194-O of the Income Tax Act. TCS is applicable when the pharmacy's annual marketplace GMV exceeds Rs 5,00,000. Below this threshold, TCS is waived and `tcs_deducted = 0`.
5. **Net payout formula**: `net_payable = GMV - commission_pct% - TCS`. TCS is deducted from the platform's commission, not from the pharmacy's GMV. Net payout is what the pharmacy receives: `pharmacy_net = GMV - commission_earned - TCS`. *(Clarification: platform takes commission + TCS from GMV; pharmacy receives remainder.)*
6. **Settlement cycle is weekly by default**: Settlements cover Monday-Sunday. Settlement records are auto-created by a settlement generation job that runs every Monday morning. The settlement period is `[last_monday_00:00, last_sunday_23:59]` IST. The settlement is held for 2 business days for fraud review before being released.
7. **Settlement release triggers RazorpayX payout**: Calling `POST /settlements/:id/release` initiates a RazorpayX payout to the pharmacy's verified bank account. If no verified bank account exists, release fails with `BANK_ACCOUNT_NOT_VERIFIED`. Payout confirmation is received via RazorpayX webhook; settlement status updates to `PAID` asynchronously.
8. **Only `admin_finance` or `admin_super` can release or hold settlements**: Other admin roles cannot release or hold. Attempting these actions returns HTTP 403.
9. **A hold requires a reason**: Calling `POST /settlements/:id/hold` requires a non-empty `reason`. The reason is stored and communicated to the pharmacy via email.
10. **Payout receipts are auto-emailed**: When a settlement status changes to `PAID` (via RazorpayX webhook confirmation), a PDF payout receipt is generated and emailed to the pharmacy owner. The receipt includes: settlement period, GMV, commission, TCS, net paid, UTR number.

---

## API Endpoints

### 1. Get Pharmacy Commission Details

```
GET /api/v1/admin/pharmacies/:id/commission
```

**Authentication:** Bearer JWT - `admin_finance`, `admin_super`, `admin_operations`
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
    "business_name": "Sharma Medical Store",
    "current_commission_pct": 8.00,
    "pending_commission_change": {
      "new_commission_pct": 7.00,
      "effective_from": "2026-07-28",
      "changed_by": "admin-uuid-v4",
      "reason": "Loyalty incentive for Q2 performance"
    },
    "tcs_applicable": true,
    "tcs_rate_pct": 1.00,
    "annual_gmv_ytd": 2850000.00,
    "tcs_threshold_crossed": true,
    "current_period": {
      "period_label": "2026-07-14 to 2026-07-20",
      "gmv": 185000.00,
      "commission_earned": 14800.00,
      "tcs_deducted": 1850.00,
      "net_payable_to_pharmacy": 168350.00,
      "settlement_status": "PENDING_RELEASE"
    },
    "bank_account_masked": "XXXXXXXXXXXX4321",
    "bank_account_verified": true,
    "last_settlement_date": "2026-07-17",
    "next_settlement_date": "2026-07-24"
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 403 | `FORBIDDEN` | Caller not admin_finance, admin_super, or admin_operations |
| 404 | `PHARMACY_NOT_FOUND` | Pharmacy ID not found |

---

### 2. Change Commission Tier

```
PATCH /api/v1/admin/pharmacies/:id/commission
```

**Authentication:** Bearer JWT - `admin_finance`, `admin_super`
**Rate Limit:** 10 req/min per admin

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| `id` | UUID | Pharmacy ID |

**Request Body (application/json):**
```json
{
  "commission_pct": "number - required, range 3.00-20.00, two decimal precision",
  "effective_from": "string - required, date YYYY-MM-DD, must be tomorrow or later",
  "reason": "string - required, max 500 chars, business justification for change",
  "notes": "string - optional, max 1000 chars, internal admin notes"
}
```

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "pharmacy_id": "uuid-v4",
    "previous_commission_pct": 8.00,
    "new_commission_pct": 7.00,
    "effective_from": "2026-07-28",
    "reason": "Loyalty incentive for Q2 performance",
    "changed_by": "admin-uuid-v4",
    "changed_at": "2026-07-24T00:00:00Z",
    "commission_history_id": "uuid-v4"
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `INVALID_COMMISSION_PCT` | Commission outside 3-20% range |
| 400 | `EFFECTIVE_FROM_MUST_BE_FUTURE` | `effective_from` is today or in the past |
| 400 | `REASON_REQUIRED` | `reason` is empty |
| 403 | `FORBIDDEN` | Caller not admin_finance or admin_super |
| 404 | `PHARMACY_NOT_FOUND` | Pharmacy ID not found |
| 409 | `PENDING_CHANGE_EXISTS` | A commission change is already scheduled; cancel it before creating a new one |

---

### 3. Get Settlement History

```
GET /api/v1/admin/pharmacies/:id/settlements
```

**Authentication:** Bearer JWT - `admin_finance`, `admin_super`, `admin_operations`
**Rate Limit:** 60 req/min

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| `id` | UUID | Pharmacy ID |

**Query Parameters:**
| Param | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `status` | string | No | ALL | PENDING_RELEASE \| RELEASED \| PAID \| HELD \| FAILED \| ALL |
| `from_date` | date | No | 90 days ago | Settlement period start filter (YYYY-MM-DD) |
| `to_date` | date | No | today | Settlement period end filter (YYYY-MM-DD) |
| `page` | integer | No | 1 | Page number |
| `limit` | integer | No | 20 | Records per page, max 50 |

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "pharmacy_id": "uuid-v4",
    "settlements": [
      {
        "settlement_id": "uuid-v4",
        "period_label": "2026-07-14 to 2026-07-20",
        "period_start": "2026-07-14",
        "period_end": "2026-07-20",
        "gmv": 185000.00,
        "commission_pct": 8.00,
        "commission_earned": 14800.00,
        "tcs_rate_pct": 1.00,
        "tcs_deducted": 1850.00,
        "net_paid": 168350.00,
        "status": "PAID",
        "released_at": "2026-07-22T10:00:00Z",
        "paid_at": "2026-07-22T10:45:00Z",
        "utr_number": "HDFC2026072212345678",
        "hold_reason": null,
        "receipt_url": "https://cdn.example.com/receipts/settlement-uuid.pdf"
      },
      {
        "settlement_id": "uuid-v4",
        "period_label": "2026-07-07 to 2026-07-13",
        "period_start": "2026-07-07",
        "period_end": "2026-07-13",
        "gmv": 162000.00,
        "commission_pct": 8.00,
        "commission_earned": 12960.00,
        "tcs_rate_pct": 1.00,
        "tcs_deducted": 1620.00,
        "net_paid": 147420.00,
        "status": "HELD",
        "released_at": null,
        "paid_at": null,
        "utr_number": null,
        "hold_reason": "Fraud review in progress for order ORD-20260710-0088",
        "receipt_url": null
      }
    ]
  },
  "meta": {
    "page": 1,
    "limit": 20,
    "total": 12,
    "total_pages": 1
  }
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 403 | `FORBIDDEN` | Caller not authorised |
| 404 | `PHARMACY_NOT_FOUND` | Pharmacy ID not found |

---

### 4. Release a Settlement

```
POST /api/v1/admin/pharmacies/:id/settlements/:settlement_id/release
```

**Authentication:** Bearer JWT - `admin_finance`, `admin_super`
**Rate Limit:** 20 req/min per admin

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| `id` | UUID | Pharmacy ID |
| `settlement_id` | UUID | Settlement ID |

**Request Body (application/json):**
```json
{
  "notes": "string - optional, internal notes for the release action, max 500 chars"
}
```

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "settlement_id": "uuid-v4",
    "status": "RELEASED",
    "released_at": "2026-07-24T00:00:00Z",
    "released_by": "admin-finance-uuid",
    "payout_initiated": true,
    "razorpayx_payout_id": "pout_XXXXXXXXXXXX",
    "estimated_credit_hours": 4,
    "message": "Settlement released. Payout initiated to pharmacy bank account."
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 403 | `FORBIDDEN` | Caller not admin_finance or admin_super |
| 404 | `SETTLEMENT_NOT_FOUND` | Settlement ID not found for this pharmacy |
| 409 | `SETTLEMENT_ALREADY_RELEASED` | Settlement already in RELEASED or PAID status |
| 409 | `SETTLEMENT_HELD` | Settlement is HELD; remove hold before releasing |
| 422 | `BANK_ACCOUNT_NOT_VERIFIED` | Pharmacy has no verified bank account |

---

### 5. Hold a Settlement

```
POST /api/v1/admin/pharmacies/:id/settlements/:settlement_id/hold
```

**Authentication:** Bearer JWT - `admin_finance`, `admin_super`
**Rate Limit:** 20 req/min per admin

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| `id` | UUID | Pharmacy ID |
| `settlement_id` | UUID | Settlement ID |

**Request Body (application/json):**
```json
{
  "reason": "string - required, max 500 chars, communicated to pharmacy in email"
}
```

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "settlement_id": "uuid-v4",
    "status": "HELD",
    "held_at": "2026-07-24T00:00:00Z",
    "held_by": "admin-finance-uuid",
    "reason": "Fraud review in progress for order ORD-20260710-0088",
    "pharmacy_notified": true
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `REASON_REQUIRED` | `reason` is empty |
| 403 | `FORBIDDEN` | Caller not admin_finance or admin_super |
| 404 | `SETTLEMENT_NOT_FOUND` | Settlement ID not found |
| 409 | `SETTLEMENT_ALREADY_PAID` | Cannot hold a settlement that is already PAID |

---

## Data Models

### Settlement

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID v4 | PK | Unique settlement record |
| `pharmacy_id` | UUID | FK ? Pharmacy.id, not null | Owning pharmacy |
| `period_start` | DATE | Not null | Settlement period start (Monday) |
| `period_end` | DATE | Not null | Settlement period end (Sunday) |
| `gmv` | DECIMAL(14,2) | Not null | Gross merchandise value for period |
| `commission_pct` | DECIMAL(5,2) | Not null | Commission rate applied |
| `commission_earned` | DECIMAL(14,2) | Not null | Platform commission (GMV - commission_pct) |
| `tcs_rate_pct` | DECIMAL(5,2) | Not null, default 1.00 | TCS rate applied |
| `tcs_deducted` | DECIMAL(14,2) | Not null | TCS amount (GMV - tcs_rate_pct if applicable) |
| `net_paid` | DECIMAL(14,2) | Not null | Amount paid to pharmacy (GMV - commission - TCS) |
| `status` | ENUM | Not null, default PENDING_RELEASE | PENDING_RELEASE \| RELEASED \| PAID \| HELD \| FAILED |
| `hold_reason` | TEXT | Nullable | Reason for hold (if status=HELD) |
| `released_by` | UUID | FK ? User.id, nullable | Admin who released |
| `released_at` | TIMESTAMPTZ | Nullable | Release timestamp |
| `paid_at` | TIMESTAMPTZ | Nullable | RazorpayX confirmation timestamp |
| `razorpayx_payout_id` | VARCHAR(100) | Nullable | RazorpayX payout ID for tracking |
| `utr_number` | VARCHAR(50) | Nullable | Bank UTR number for completed payout |
| `receipt_url` | TEXT | Nullable | CDN URL of generated PDF receipt |
| `created_at` | TIMESTAMPTZ | Not null, default now() | Settlement record creation |

### CommissionHistory

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID v4 | PK | History record ID |
| `pharmacy_id` | UUID | FK ? Pharmacy.id, not null | Pharmacy whose commission changed |
| `previous_commission_pct` | DECIMAL(5,2) | Not null | Commission before change |
| `new_commission_pct` | DECIMAL(5,2) | Not null | Commission after change |
| `effective_from` | DATE | Not null | Date change takes effect |
| `reason` | TEXT | Not null | Business justification |
| `notes` | TEXT | Nullable | Internal admin notes |
| `changed_by` | UUID | FK ? User.id, not null | Admin who made the change |
| `changed_at` | TIMESTAMPTZ | Not null, default now() | When the change was scheduled |
| `applied_at` | TIMESTAMPTZ | Nullable | When the change was actually applied (midnight on effective_from) |

---

## Acceptance Criteria

- [ ] **Given** GET `/api/v1/admin/pharmacies/:id/commission`, **then** the response includes `current_commission_pct`, any pending change, TCS applicability, current period GMV/commission/TCS/net_payable, bank account masked and verification status.
- [ ] **Given** PATCH `/api/v1/admin/pharmacies/:id/commission` with `commission_pct=25.0`, **then** HTTP 400 `INVALID_COMMISSION_PCT` is returned (exceeds 20% max).
- [ ] **Given** PATCH `/api/v1/admin/pharmacies/:id/commission` with `effective_from` = today, **then** HTTP 400 `EFFECTIVE_FROM_MUST_BE_FUTURE` is returned.
- [ ] **Given** a valid commission change is submitted, **then** a `CommissionHistory` record is created and an `AuditLog` entry is written with `action=COMMISSION_CHANGED`, `before_value`, `after_value`, and `actor_id`.
- [ ] **Given** a pharmacy's annual GMV YTD exceeds Rs 5,00,000, **then** `tcs_applicable=true` and `tcs_deducted = GMV - 1%` in the settlement; below threshold, `tcs_deducted=0`.
- [ ] **Given** POST `/api/v1/admin/pharmacies/:id/settlements/:settlement_id/release` on a settlement in `PENDING_RELEASE` status with a verified bank account, **then** RazorpayX payout is initiated, settlement status changes to `RELEASED`, and the pharmacy owner receives a payout confirmation email with UTR.
- [ ] **Given** POST `/release` is called on a settlement in `HELD` status, **then** HTTP 409 `SETTLEMENT_HELD` is returned.
- [ ] **Given** POST `/api/v1/admin/pharmacies/:id/settlements/:settlement_id/hold` with a `reason`, **then** settlement status changes to `HELD`, the pharmacy owner is notified via email with the hold reason, and the reason is recorded in the settlement record.

---

## Dependencies

- STORY-003-005 - Pharmacy Profile Update (bank account verification required for release)
- STORY-004-001 - Pharmacy Directory (commission shown in detail drawer)
- EPIC-008 - Orders (GMV aggregation per settlement period)
- External: RazorpayX - Payout API for settlement disbursements and penny drop
- External: PDF Generation Service - Payout receipt generation
- Infrastructure: Settlement generation cron job (runs every Monday)

---

## Notes

- Section 194-O TCS is collected by the platform as the e-commerce operator. The platform must file TCS returns quarterly (Form 27EQ). Integration with the tax filing module (EPIC-012) is out of scope here but the settlement data must expose TCS amounts in a format consumable by the tax module.
- The settlement generation job creates `Settlement` records in `PENDING_RELEASE` status automatically. The 2 business-day fraud review hold is enforced by not auto-releasing until the hold window passes. After the window, settlements are flagged for admin release (not auto-released).
- If RazorpayX payout fails (e.g., bank account error), settlement status changes to `FAILED`. An admin alert is generated and the pharmacy is notified. Admin must investigate and re-release.
- Commission change scheduled job: a cron at 00:01 IST daily checks `CommissionHistory` for records where `effective_from = today` and `applied_at IS NULL`. It updates `Pharmacy.commission_pct` and sets `applied_at`.
- Net paid to pharmacy: `pharmacy_net = GMV - commission_earned - tcs_deducted`. Example: GMV=100,000; commission=8,000; TCS=1,000; pharmacy_net=91,000.
