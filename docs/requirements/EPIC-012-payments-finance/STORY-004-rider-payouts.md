# STORY-004: Rider Payouts

| Field | Value |
|---|---|
| Story ID | EPIC-012/STORY-004 |
| Epic | EPIC-012 - Payments and Finance |
| Title | Rider Payouts |
| Status | Draft |
| Priority | P0 |
| Estimated Effort | 1 Sprint |
| Last Updated | 2026-07-24 |

---

## Overview

This story covers the finance-side view and management of weekly rider earnings payouts. Rider earnings (base pay, incentives, tips, streak bonuses) are computed from the `RiderEarningsLedger` by EPIC-011/STORY-008's cron; this story exposes the admin interfaces for monitoring, reviewing, and releasing those payouts via Cashfree Payouts. It also covers bulk release, the hold/release workflow for riders with unresolved COD float, and the rider-facing payout history endpoint. Payout failures are auto-retried once and then escalated to admin_finance.

---

## User Roles

| Role | Capability |
|---|---|
| `admin_finance` | View payout table, view ledger, release individual/bulk payouts |
| `admin_super` | All admin_finance capabilities |
| `rider` | View own payout history |

---

## Business Rules

| # | Rule |
|---|---|
| BR-001 | Rider payout cycle mirrors pharmacy settlement cycle: Monday 00:00 to Sunday 23:59 IST; payouts queued Monday morning for the previous week. |
| BR-002 | **Net payout formula:** `net_payout = base_earnings + incentives + tips + streak_bonus ? cod_in_hand_deducted`. |
| BR-003 | If a rider's `cod_in_hand > cod_float_limit` at payout computation time, payout status is automatically set to `HELD`; it is released only after admin_finance review and COD resolution. |
| BR-004 | **Minimum threshold:** `net_payout < Rs 100` ? payout is not released; amount is carried to the next cycle with status `BELOW_THRESHOLD_CARRIED`. |
| BR-005 | Bulk release applies to all PENDING rider payouts with `net_payout ? Rs 10,000`; payouts above this threshold require individual review. |
| BR-006 | Payout is disbursed via **Cashfree Payouts** to the rider's registered UPI ID or bank account; on success, the rider receives an SMS notification. |
| BR-007 | A failed Cashfree payout is **auto-retried once** after 24 hours; if the retry fails, status is set to `FAILED` and an alert is sent to admin_finance. |
| BR-008 | `cod_in_hand_deducted` in the payout record represents the net COD cash the rider held at payout time, not yet deposited. This amount reduces the payout since the rider already holds the cash. |

---

## API Endpoints

### GET /api/v1/admin/finance/rider-payouts

**Auth:** `Bearer JWT` (admin_finance, admin_super)  
**Description:** Rider payouts table for the current or specified cycle.

**Query Params:** `?cycle_from=2026-07-14&status=PENDING&zone_id=<uuid>&page=1&limit=20`

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "summary": {
      "total_pending": 48,
      "total_pending_amount": 72400.00,
      "total_held": 3,
      "total_held_amount": 4800.00,
      "total_released_this_cycle": 12,
      "total_released_amount": 18200.00
    },
    "payouts": [
      {
        "payout_id": "payout_uuid",
        "rider_id": "rider_uuid",
        "rider_name": "Ravi Kumar",
        "zone_name": "Koramangala",
        "cycle_from": "2026-07-14",
        "cycle_to": "2026-07-20",
        "base_earnings": 1680.00,
        "incentives": 150.00,
        "tips": 70.00,
        "streak_bonus": 100.00,
        "cod_deducted": 0.00,
        "net_payout": 2000.00,
        "status": "PENDING",
        "payout_cycle": "2026-W29"
      }
    ]
  },
  "meta": {
    "page": 1,
    "limit": 20,
    "total": 63
  }
}
```

---

### GET /api/v1/admin/finance/rider-payouts/:rider_id/ledger

**Auth:** `Bearer JWT` (admin_finance, admin_super)  
**Description:** Full earnings ledger (per-day, per-order) for a specific rider.

**Query Params:** `?from=2026-07-14&to=2026-07-20&page=1&limit=50`

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "rider_id": "rider_uuid",
    "rider_name": "Ravi Kumar",
    "entries": [
      {
        "date": "2026-07-14",
        "order_id": "order_uuid",
        "order_number": "MED-20260714-010",
        "base_pay": 20.00,
        "tip": 10.00,
        "incentive_bonus": 5.00,
        "total": 35.00,
        "on_time": true,
        "distance_km": 2.4,
        "completed_at": "2026-07-14T11:30:00Z"
      }
    ],
    "cycle_summary": {
      "base_earnings": 1680.00,
      "incentives": 150.00,
      "tips": 70.00,
      "streak_bonus": 100.00,
      "cod_deducted": 0.00,
      "net_payout": 2000.00
    }
  },
  "meta": {
    "page": 1,
    "limit": 50,
    "total": 78
  }
}
```

---

### POST /api/v1/admin/finance/rider-payouts/:rider_id/release

**Auth:** `Bearer JWT` (admin_finance, admin_super)  
**Description:** Manually release a rider's payout for the specified cycle.

**Request Body:**
```json
{
  "payout_id": "payout_uuid",
  "notes": "Manual release after COD resolved."
}
```

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "payout_id": "payout_uuid",
    "rider_id": "rider_uuid",
    "net_payout": 2000.00,
    "status": "RELEASED",
    "cashfree_transfer_id": "pout_XXXXXXXXXXXX",
    "released_by": "admin_uuid",
    "released_at": "2026-07-24T10:00:00Z"
  },
  "meta": {}
}
```

**Errors:**
| Code | HTTP | Meaning |
|---|---|---|
| `PAYOUT_NOT_FOUND` | 404 | payout_id does not exist |
| `PAYOUT_ALREADY_RELEASED` | 409 | Payout already released |
| `PAYOUT_BELOW_THRESHOLD` | 422 | net_payout < Rs 100 |
| `COD_UNRESOLVED` | 422 | Rider cod_in_hand still > cod_float_limit |
| `RIDER_NO_PAYMENT_DETAILS` | 422 | Rider has no registered UPI/bank account |
| `CASHFREE_PAYOUT_FAILED` | 502 | Cashfree Payouts API error |

---

### POST /api/v1/admin/finance/rider-payouts/release-all

**Auth:** `Bearer JWT` (admin_finance, admin_super)  
**Description:** Bulk release all PENDING rider payouts at or below Rs 10,000.

**Request Body:**
```json
{
  "threshold": 10000.00,
  "cycle_from": "2026-07-14",
  "notes": "Routine Monday bulk rider payout."
}
```

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "attempted": 48,
    "released": 44,
    "failed": 4,
    "total_amount_released": 62400.00,
    "failures": [
      {
        "payout_id": "payout_uuid_2",
        "rider_name": "Suresh M",
        "reason": "COD_UNRESOLVED"
      }
    ]
  },
  "meta": {}
}
```

---

### GET /api/v1/rider/payouts/history

**Auth:** `Bearer JWT` (rider)  
**Description:** Rider's own payout history.

**Query Params:** `?page=1&limit=20`

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "payouts": [
      {
        "payout_id": "payout_uuid",
        "cycle_from": "2026-07-14",
        "cycle_to": "2026-07-20",
        "base_earnings": 1680.00,
        "incentives": 150.00,
        "tips": 70.00,
        "streak_bonus": 100.00,
        "cod_deducted": 0.00,
        "net_payout": 2000.00,
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

## Data Models

### RiderPayout (canonical - see also EPIC-011/STORY-008)

| Field | Type | Nullable | Description |
|---|---|---|---|
| `id` | UUID v4 | No | Primary key |
| `rider_id` | UUID | No | FK ? RiderProfile |
| `cycle_from` | DATE | No | Mon-Sun cycle start |
| `cycle_to` | DATE | No | Mon-Sun cycle end |
| `payout_cycle` | VARCHAR(10) | No | ISO week label e.g. `2026-W29` |
| `base_earnings` | DECIMAL(12,2) | No | Base pay total for cycle |
| `incentives` | DECIMAL(12,2) | No | Incentive bonuses |
| `tips` | DECIMAL(12,2) | No | Customer tips |
| `streak_bonus` | DECIMAL(8,2) | No | 7-day streak bonus |
| `cod_deducted` | DECIMAL(12,2) | No | COD in_hand deducted |
| `net_payout` | DECIMAL(12,2) | No | Final payable amount |
| `status` | ENUM(`PENDING`,`HELD`,`RELEASED`,`FAILED`,`BELOW_THRESHOLD_CARRIED`) | No | Payout lifecycle |
| `hold_reason` | TEXT | Yes | COD or compliance hold reason |
| `cashfree_transfer_id` | VARCHAR(100) | Yes | Cashfree Payouts transfer reference |
| `retry_count` | SMALLINT | No | Auto-retry count (0 or 1) |
| `retry_at` | TIMESTAMPTZ | Yes | Scheduled retry time |
| `released_by` | UUID | Yes | FK ? AdminUser |
| `released_at` | TIMESTAMPTZ | Yes | Release timestamp |
| `notes` | TEXT | Yes | Admin release notes |
| `created_at` | TIMESTAMPTZ | No | Payout record creation |

---

## Acceptance Criteria

| # | Criterion |
|---|---|
| AC-001 | Monday morning cron creates a `RiderPayout` record for each rider who had at least one delivery in the cycle. |
| AC-002 | A rider with `cod_in_hand > Rs 2,000` at computation time has payout auto-set to `HELD`; releasing it via the API without COD resolution returns HTTP 422 `COD_UNRESOLVED`. |
| AC-003 | A payout with `net_payout < Rs 100` is not released; it gets status `BELOW_THRESHOLD_CARRIED` and the amount is added to the next cycle. |
| AC-004 | Successful `POST /admin/finance/rider-payouts/:id/release` triggers a Cashfree Payouts transfer and the rider receives an SMS with the payout amount. |
| AC-005 | A failed Cashfree payout is automatically retried once after 24 hours; if the retry fails, status is `FAILED` and admin_finance receives an alert. |
| AC-006 | Bulk release skips riders with `HELD` status, `net_payout > Rs 10,000`, and `net_payout < Rs 100`; returns a summary with release count and failure reasons. |
| AC-007 | `GET /rider/payouts/history` returns only the authenticated rider's own payout records. |
| AC-008 | Each payout release creates a `FinancialLedger` entry with type `PAYOUT_RIDER`. |

---

## Dependencies

| Dependency | Type | Notes |
|---|---|---|
| Rider Incentives (EPIC-011/STORY-008) | Internal | `RiderEarningsLedger` is source for payout computation |
| COD Reconciliation (EPIC-011/STORY-007) | Internal | `cod_in_hand` used for hold/deduction logic |
| Cashfree Payouts | External | UPI/bank disbursement |
| Financial Ledger (EPIC-012/STORY-008) | Internal | Ledger entry on payout release |
| Notification Service (EPIC-013) | Internal | SMS to rider on payout success; alert to admin on failure |
| Scheduled Job Runner | Internal | Monday cron + auto-retry scheduler |

---

## Notes

- The `RiderPayout` model is shared between EPIC-011/STORY-008 (rider-facing view) and this story (finance admin view); they reference the same table.
- Auto-retry is implemented via a scheduled job that checks for `status = FAILED` payouts with `retry_count = 0` older than 24 hours.
