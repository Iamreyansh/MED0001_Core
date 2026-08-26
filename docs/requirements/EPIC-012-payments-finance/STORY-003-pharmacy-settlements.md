# STORY-003: Pharmacy Settlements

| Field | Value |
|---|---|
| Story ID | EPIC-012/STORY-003 |
| Epic | EPIC-012 - Payments and Finance |
| Title | Pharmacy Settlements |
| Status | Draft |
| Priority | P0 |
| Estimated Effort | 2 Sprints |
| Last Updated | 2026-07-24 |

---

## Overview

This story governs the weekly settlement of funds owed to pharmacies for orders fulfilled on the Namma MedMate platform. Every Monday, a cron job auto-generates settlement records for each pharmacy covering the previous Mon-Sun cycle. The net payable is computed as `GMV - commission_pct ? TCS (1%)`. Admins can release, hold, or bulk-release settlements via Admin HQ. Release triggers an immediate Cashfree Payouts transfer to the pharmacy's verified bank account, followed by an automated email and WhatsApp notification with the settlement details. Pharmacies can view their own settlement history in the Pharmacy Dashboard.

---

## User Roles

| Role | Capability |
|---|---|
| `admin_finance` | View, release, hold settlements, bulk release |
| `admin_super` | All admin_finance capabilities |
| `pharmacy_owner` | View own settlement history and detail |
| System | Auto-generate settlements on Monday morning |

---

## Business Rules

| # | Rule |
|---|---|
| BR-001 | **Settlement cycle:** Monday 00:00 IST to Sunday 23:59 IST. A new settlement record is auto-generated every Monday at 06:00 IST for the previous full week. |
| BR-002 | **Net payable formula:** `net_payable = GMV ? (GMV - commission_pct) ? (GMV - 0.01)`. Where `GMV - commission_pct` is retained by the platform as commission and `GMV - 0.01` is TCS deducted. |
| BR-003 | Only orders in `DELIVERED` status with `payment_status = CAPTURED` or `COLLECTED_COD` are included in the GMV for a settlement cycle. |
| BR-004 | Only pharmacies with `status = ACTIVE` and a **verified bank account** on file receive settlements. |
| BR-005 | A settlement below **Rs 100** is not released; the amount is carried forward and added to the next cycle's settlement. |
| BR-006 | Releasing a settlement triggers a **Cashfree Payouts transfer** to the pharmacy's bank account; the payout receipt is auto-emailed and sent via WhatsApp to the pharmacy owner. |
| BR-007 | A `HELD` settlement requires an admin_finance decision to release or carry forward; hold reason is recorded. |
| BR-008 | **Bulk release** applies only to settlements with `status = PENDING` and `net_payable ? Rs 50,000`; larger settlements require individual review and release. |
| BR-009 | TCS deduction is tracked per pharmacy per month in `TCSRegister` for GSTR-8 filing. |

---

## API Endpoints

### GET /api/v1/admin/finance/settlements

**Auth:** `Bearer JWT` (admin_finance, admin_super)  
**Description:** Settlement list with KPI chips. Filterable by status, pharmacy, cycle.

**Query Params:** `?status=PENDING&pharmacy_id=<uuid>&cycle_from=2026-07-14&page=1&limit=20`

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "kpi_chips": {
      "gmv_today": 185000.00,
      "commission_today": 14800.00,
      "payout_due_total": 420500.00,
      "payout_released_today": 95000.00
    },
    "settlements": [
      {
        "settlement_id": "settlement_uuid",
        "pharmacy_id": "pharmacy_uuid",
        "pharmacy_name": "Apollo Pharmacy, Koramangala",
        "cycle_from": "2026-07-14",
        "cycle_to": "2026-07-20",
        "gmv": 52000.00,
        "commission_pct": 8.0,
        "commission_earned": 4160.00,
        "tcs_deducted": 520.00,
        "net_payable": 47320.00,
        "status": "PENDING",
        "released_at": null
      }
    ],
    "totals": {
      "total_gmv": 420000.00,
      "total_commission": 33600.00,
      "total_tcs": 4200.00,
      "total_net_payable": 382200.00
    }
  },
  "meta": {
    "page": 1,
    "limit": 20,
    "total": 24
  }
}
```

---

### GET /api/v1/admin/finance/settlements/:settlement_id

**Auth:** `Bearer JWT` (admin_finance, admin_super)  
**Description:** Settlement detail with per-order line items and P&L summary.

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "settlement_id": "settlement_uuid",
    "pharmacy_id": "pharmacy_uuid",
    "pharmacy_name": "Apollo Pharmacy, Koramangala",
    "pharmacy_bank": {
      "account_number_masked": "XXXXXXXXXXXX4521",
      "bank_name": "HDFC Bank",
      "ifsc": "HDFC0001234"
    },
    "cycle_from": "2026-07-14",
    "cycle_to": "2026-07-20",
    "gmv": 52000.00,
    "commission_pct": 8.0,
    "commission_earned": 4160.00,
    "tcs_deducted": 520.00,
    "gst_on_commission": 748.80,
    "net_payable": 47320.00,
    "status": "PENDING",
    "orders_count": 148,
    "line_items": [
      {
        "order_id": "order_uuid",
        "order_number": "MED-20260714-001",
        "delivered_at": "2026-07-14T10:30:00Z",
        "gmv": 350.00,
        "commission_pct": 8.0,
        "commission": 28.00,
        "tcs": 3.50,
        "net": 318.50
      }
    ]
  },
  "meta": {}
}
```

---

### POST /api/v1/admin/finance/settlements/:settlement_id/release

**Auth:** `Bearer JWT` (admin_finance, admin_super)  
**Description:** Release a pharmacy settlement. Triggers Cashfree Payouts transfer.

**Request Body:**
```json
{
  "notes": "Routine weekly release."
}
```

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "settlement_id": "settlement_uuid",
    "pharmacy_id": "pharmacy_uuid",
    "net_payable": 47320.00,
    "status": "RELEASED",
    "cashfree_transfer_id": "pout_XXXXXXXXXXXX",
    "released_by": "admin_uuid",
    "released_at": "2026-07-24T10:00:00Z",
    "notification_sent": true
  },
  "meta": {}
}
```

**Errors:**
| Code | HTTP | Meaning |
|---|---|---|
| `SETTLEMENT_NOT_FOUND` | 404 | settlement_id does not exist |
| `ALREADY_RELEASED` | 409 | Settlement already released |
| `SETTLEMENT_HELD` | 422 | Settlement is on HOLD; unhold before releasing |
| `PHARMACY_NO_BANK_ACCOUNT` | 422 | Pharmacy has no verified bank account |
| `AMOUNT_BELOW_THRESHOLD` | 422 | net_payable < Rs 100; will be carried forward |
| `CASHFREE_PAYOUT_FAILED` | 502 | Cashfree Payouts API error |

---

### POST /api/v1/admin/finance/settlements/:settlement_id/hold

**Auth:** `Bearer JWT` (admin_finance, admin_super)  
**Description:** Place a settlement on hold.

**Request Body:**
```json
{
  "reason": "Compliance investigation open on this pharmacy.",
  "notes": "Case #COMP-20260724-001. Do not release until cleared."
}
```

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "settlement_id": "settlement_uuid",
    "status": "HELD",
    "held_by": "admin_uuid",
    "held_at": "2026-07-24T10:05:00Z",
    "reason": "Compliance investigation open on this pharmacy."
  },
  "meta": {}
}
```

---

### POST /api/v1/admin/finance/settlements/release-all

**Auth:** `Bearer JWT` (admin_finance, admin_super)  
**Description:** Bulk release all PENDING settlements at or below Rs 50,000.

**Request Body:**
```json
{
  "threshold": 50000.00,
  "notes": "Routine Monday bulk release."
}
```

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "attempted": 20,
    "released": 18,
    "failed": 2,
    "total_amount_released": 682400.00,
    "failures": [
      {
        "settlement_id": "settlement_uuid_2",
        "pharmacy_name": "MedPlus, HSR Layout",
        "reason": "PHARMACY_NO_BANK_ACCOUNT"
      }
    ]
  },
  "meta": {}
}
```

---

### GET /api/v1/pharmacy/finance/settlements

**Auth:** `Bearer JWT` (pharmacy_owner)  
**Description:** Pharmacy's own settlement history.

**Query Params:** `?page=1&limit=20&status=RELEASED`

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "settlements": [
      {
        "settlement_id": "settlement_uuid",
        "cycle_from": "2026-07-14",
        "cycle_to": "2026-07-20",
        "gmv": 52000.00,
        "commission_pct": 8.0,
        "commission_deducted": 4160.00,
        "tcs_deducted": 520.00,
        "net_payable": 47320.00,
        "status": "RELEASED",
        "released_at": "2026-07-21T06:00:00Z"
      }
    ]
  },
  "meta": {
    "page": 1,
    "limit": 20,
    "total": 12
  }
}
```

---

### GET /api/v1/pharmacy/finance/settlements/:id

**Auth:** `Bearer JWT` (pharmacy_owner)  
**Description:** Pharmacy's own settlement detail with order line items.

*(Response structure mirrors admin detail endpoint, omitting bank account and admin fields.)*

---

## Data Models

### PharmacySettlement

| Field | Type | Nullable | Description |
|---|---|---|---|
| `id` | UUID v4 | No | Primary key |
| `pharmacy_id` | UUID | No | FK ? Pharmacy |
| `cycle_from` | DATE | No | Cycle start date (Monday) |
| `cycle_to` | DATE | No | Cycle end date (Sunday) |
| `orders_count` | INTEGER | No | Delivered orders in cycle |
| `gmv` | DECIMAL(14,2) | No | Gross merchandise value |
| `commission_pct` | DECIMAL(5,2) | No | Commission rate applied |
| `commission_earned` | DECIMAL(12,2) | No | Platform commission |
| `tcs_deducted` | DECIMAL(12,2) | No | TCS at 1% of GMV |
| `gst_on_commission` | DECIMAL(12,2) | No | GST 18% on commission |
| `net_payable` | DECIMAL(12,2) | No | Amount payable to pharmacy |
| `status` | ENUM(`PENDING`,`HELD`,`RELEASED`,`BELOW_THRESHOLD_CARRIED`) | No | Settlement state |
| `hold_reason` | TEXT | Yes | Hold reason text |
| `held_by` | UUID | Yes | FK ? AdminUser |
| `held_at` | TIMESTAMPTZ | Yes | Hold timestamp |
| `cashfree_transfer_id` | VARCHAR(100) | Yes | Cashfree Payouts transfer reference |
| `released_by` | UUID | Yes | FK ? AdminUser |
| `released_at` | TIMESTAMPTZ | Yes | Release timestamp |
| `notes` | TEXT | Yes | Admin release/hold notes |
| `created_at` | TIMESTAMPTZ | No | Auto-generation timestamp |

### SettlementLineItem

| Field | Type | Nullable | Description |
|---|---|---|---|
| `id` | UUID v4 | No | Primary key |
| `settlement_id` | UUID | No | FK ? PharmacySettlement |
| `order_id` | UUID | No | FK ? Order |
| `order_number` | VARCHAR(30) | No | Human-readable order number |
| `delivered_at` | TIMESTAMPTZ | No | Order delivery timestamp |
| `gmv` | DECIMAL(12,2) | No | Order GMV |
| `commission_pct` | DECIMAL(5,2) | No | Commission rate for this order |
| `commission` | DECIMAL(10,2) | No | Commission amount |
| `tcs` | DECIMAL(10,2) | No | TCS amount (1% of GMV) |
| `net` | DECIMAL(10,2) | No | Net payable for this order |

---

## Acceptance Criteria

| # | Criterion |
|---|---|
| AC-001 | Settlement auto-generation cron creates a `PharmacySettlement` record for each ACTIVE pharmacy with verified bank account every Monday at 06:00 IST for the previous Mon-Sun cycle. |
| AC-002 | `net_payable` is correctly computed: GMV Rs 52,000, commission 8%, TCS 1% ? commission Rs 4,160, TCS Rs 520, net Rs 47,320. |
| AC-003 | Releasing a settlement with `net_payable < Rs 100` returns HTTP 422 `AMOUNT_BELOW_THRESHOLD` and carries the amount to the next cycle. |
| AC-004 | Releasing a HELD settlement returns HTTP 422 `SETTLEMENT_HELD`; admin must call the unhold endpoint first. |
| AC-005 | Releasing a settlement triggers a Cashfree Payouts transfer; the pharmacy owner receives an email and WhatsApp notification with the settlement breakdown. |
| AC-006 | Bulk release skips settlements > Rs 50,000 and those with HELD status; it returns a summary with released count and failed reasons. |
| AC-007 | `GET /pharmacy/finance/settlements/:id` returns only settlements belonging to the authenticated pharmacy_owner; requesting another pharmacy's settlement returns HTTP 403. |
| AC-008 | Each settlement release creates a `FinancialLedger` entry with type `PAYOUT_PHARMACY`. |

---

## Dependencies

| Dependency | Type | Notes |
|---|---|---|
| Order Management (EPIC-010) | Internal | Delivered orders feed GMV calculation |
| Cashfree Payouts | External | Pharmacy bank account payout |
| Tax Module (EPIC-012/STORY-007) | Internal | TCS register updated on each settlement generation |
| Financial Ledger (EPIC-012/STORY-008) | Internal | Ledger entry on payout release |
| Notification Service (EPIC-013) | Internal | Email + WhatsApp to pharmacy on settlement release |
| Pharmacy Management (EPIC-003) | Internal | Commission rate, bank account, ACTIVE status |
| Scheduled Job Runner | Internal | Monday 06:00 IST settlement generation cron |

---

## Notes

- The `commission_pct` stored on each `SettlementLineItem` is the rate **at the time of order delivery**, not the current rate; this handles commission rate changes mid-cycle correctly.
- GST on commission (18%) is informational in the settlement detail for the pharmacy's accounting; it is **not** an additional deduction from net_payable - the platform collects GST separately via its own GST registration.
- A pharmacy with no deliveries in a cycle does not get a settlement record generated; the cron skips zero-GMV pharmacies.
