# STORY-008: Rider Incentives & Performance

| Field | Value |
|---|---|
| Story ID | EPIC-011/STORY-008 |
| Epic | EPIC-011 - Rider Management and Delivery |
| Title | Rider Incentives & Performance |
| Status | Draft |
| Priority | P1 |
| Estimated Effort | 2 Sprints |
| Last Updated | 2026-07-24 |

---

## Overview

This story covers the complete rider earnings lifecycle - from per-trip base pay and customer tips, through streak bonuses and configurable incentive rules, to the weekly payout cycle. Riders view their earnings dashboard and performance metrics via the Rider App. Admins view the payout ledger, full performance profile, and can manually trigger payout releases. Incentive rules (streak thresholds, bonus amounts, on-time targets) are configurable via the Automation Engine module (EPIC-015); this story defines the data structures, computation endpoints, and release flow.

---

## User Roles

| Role | Capability |
|---|---|
| `rider` | View own earnings dashboard, performance metrics, trip history |
| `admin_finance` | View and release rider payouts, view earnings ledger |
| `admin_operations` | View rider performance for dispatch decisions |
| `admin_super` | All of the above |

---

## Business Rules

| # | Rule |
|---|---|
| BR-001 | **Base pay per delivery** = Rs 15 (distance < 2 km) to Rs 25 (distance ? 5 km); linear interpolation for distances in between. Base pay rates are configurable in system settings. |
| BR-002 | **Streak bonus:** a 7-consecutive-day active streak earns the rider Rs 100 bonus added to that week's payout. `daily_streak_days` increments when a rider completes at least 1 delivery in a day and resets to 0 if a full calendar day passes without any delivery. |
| BR-003 | **On-time flag:** a delivery is `on_time = true` if the `delivered_at` timestamp is within `zone.sla_minutes` of the order's `accepted_at` timestamp. |
| BR-004 | **Weekly payout cycle:** Monday 00:00 IST to Sunday 23:59 IST. Payouts are computed and queued Monday morning for the previous week. |
| BR-005 | **Minimum payout threshold:** Rs 100. If a rider's net payout for the week is below Rs 100, the amount is carried forward to the next cycle; no payout is released. |
| BR-006 | **Payout composition:** `net_payout = base_earnings + incentives + tips ? cod_in_hand_deducted`. `cod_in_hand_deducted` = total undeposited COD cash at time of payout computation. |
| BR-007 | If a rider has unresolved `cod_in_hand > cod_float_limit`, the payout is placed in `HELD` status until COD is resolved; admin_finance must manually review and release. |
| BR-008 | Payout is disbursed via **Cashfree Payouts** to the rider's registered UPI ID or bank account; the rider receives an SMS notification on successful payout. |
| BR-009 | Failed payouts (Cashfree failure) are auto-retried **once** 24 hours later; if the retry fails, the payout is flagged `FAILED` for admin_finance review. |
| BR-010 | **Acceptance rate** tracks `(orders accepted / orders assigned) - 100`; consistently low acceptance rate (< 70%) triggers an alert to admin_operations. |

---

## API Endpoints

### GET /api/v1/rider/earnings

**Auth:** `Bearer JWT` (rider)  
**Description:** Rider earnings dashboard - today, this week, lifetime, and next payout info.

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "rider_id": "rider_uuid",
    "today": {
      "base": 375.00,
      "incentives": 50.00,
      "tips": 20.00,
      "total": 445.00,
      "trips": 15
    },
    "this_week": {
      "base": 1875.00,
      "incentives": 200.00,
      "tips": 85.00,
      "total": 2160.00,
      "trips": 75,
      "cycle_from": "2026-07-21",
      "cycle_to": "2026-07-27"
    },
    "lifetime": {
      "total_earnings": 48500.00,
      "total_trips": 1924
    },
    "wallet_balance": 2160.00,
    "streak": {
      "current_days": 5,
      "streak_bonus_at_days": 7,
      "streak_bonus_amount": 100.00,
      "days_remaining_for_bonus": 2
    },
    "next_payout": {
      "estimated_amount": 2160.00,
      "date": "2026-07-28",
      "cod_deduction_expected": 0.00
    }
  },
  "meta": {}
}
```

---

### GET /api/v1/rider/performance

**Auth:** `Bearer JWT` (rider)  
**Description:** Rider's own performance metrics.

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "rider_id": "rider_uuid",
    "trips_total": 1924,
    "trips_today": 15,
    "trips_this_week": 75,
    "on_time_pct": 91.2,
    "acceptance_rate_pct": 94.5,
    "avg_rating": 4.72,
    "avg_pickup_minutes": 6.4,
    "avg_delivery_minutes": 17.8,
    "cancel_rate_pct": 1.2,
    "total_distance_km": 4821.3,
    "badges": [
      { "badge": "SPEED_STAR", "earned_at": "2026-07-01" },
      { "badge": "5_STAR_WEEK", "earned_at": "2026-07-14" }
    ]
  },
  "meta": {}
}
```

---

### GET /api/v1/rider/trips

**Auth:** `Bearer JWT` (rider)  
**Description:** Rider's trip history with per-trip earnings breakdown.

**Query Params:** `?page=1&limit=20&from=2026-07-01&to=2026-07-24`

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "trips": [
      {
        "order_id": "order_uuid",
        "order_number": "MED-20260724-015",
        "pickup_pharmacy": "Apollo Pharmacy, Koramangala",
        "delivery_area": "HSR Layout, Sector 2",
        "distance_km": 2.4,
        "duration_minutes": 14,
        "base_pay": 20.00,
        "tip": 10.00,
        "incentive_bonus": 0.00,
        "total_earned": 30.00,
        "on_time": true,
        "customer_rating": 5,
        "completed_at": "2026-07-24T13:48:00Z"
      }
    ]
  },
  "meta": {
    "page": 1,
    "limit": 20,
    "total": 1924
  }
}
```

---

### GET /api/v1/admin/riders/:id/earnings-ledger

**Auth:** `Bearer JWT` (admin_finance, admin_super)  
**Description:** Admin view of a rider's payout ledger across weekly cycles.

**Query Params:** `?page=1&limit=20&from=2026-07-01&to=2026-07-24`

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "rider_id": "rider_uuid",
    "rider_name": "Ravi Kumar",
    "ledger": [
      {
        "payout_id": "payout_uuid",
        "cycle_from": "2026-07-14",
        "cycle_to": "2026-07-20",
        "base_earnings": 1680.00,
        "incentives": 150.00,
        "tips": 70.00,
        "streak_bonus": 100.00,
        "cod_deducted": 500.00,
        "net_payout": 1500.00,
        "payout_status": "RELEASED",
        "payout_reference": "RPX-20260721-001",
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

### GET /api/v1/admin/riders/:id/performance

**Auth:** `Bearer JWT` (admin_operations, admin_finance, admin_super)  
**Description:** Admin view of a rider's full performance profile.

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "rider_id": "rider_uuid",
    "name": "Ravi Kumar",
    "zone_name": "Koramangala",
    "trips_total": 1924,
    "trips_this_week": 75,
    "on_time_pct": 91.2,
    "acceptance_rate_pct": 94.5,
    "avg_rating": 4.72,
    "avg_pickup_minutes": 6.4,
    "avg_delivery_minutes": 17.8,
    "cancel_rate_pct": 1.2,
    "total_distance_km": 4821.3,
    "cod_in_hand": 1850.00,
    "cod_float_limit": 2000.00,
    "risk_status": "SAFE",
    "daily_streak_days": 5,
    "alerts": [
      { "type": "ACCEPTANCE_RATE_LOW", "value": "62% this week", "threshold": "70%" }
    ]
  },
  "meta": {}
}
```

---

### POST /api/v1/admin/riders/:id/payout/release

**Auth:** `Bearer JWT` (admin_finance, admin_super)  
**Description:** Manually release a rider's pending payout for the specified cycle.

**Request Body:**
```json
{
  "payout_id": "payout_uuid",
  "notes": "Manual release approved after COD resolved."
}
```

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "payout_id": "payout_uuid",
    "rider_id": "rider_uuid",
    "net_payout": 1500.00,
    "payout_status": "RELEASED",
    "cashfree_transfer_id": "pout_uuid",
    "released_by": "admin_uuid",
    "released_at": "2026-07-24T16:00:00Z"
  },
  "meta": {}
}
```

**Errors:**
| Code | HTTP | Meaning |
|---|---|---|
| `PAYOUT_NOT_FOUND` | 404 | payout_id does not exist |
| `PAYOUT_ALREADY_RELEASED` | 409 | Payout already in RELEASED state |
| `PAYOUT_BELOW_THRESHOLD` | 422 | Net payout < Rs 100 minimum |
| `COD_UNRESOLVED` | 422 | Rider has unresolved COD float > limit |

---

## Data Models

### RiderEarningsLedger

| Field | Type | Nullable | Description |
|---|---|---|---|
| `id` | UUID v4 | No | Primary key |
| `rider_id` | UUID | No | FK ? RiderProfile |
| `order_id` | UUID | No | FK ? Order |
| `date` | DATE | No | Calendar date of delivery |
| `base_pay` | DECIMAL(8,2) | No | Base pay for this trip |
| `tip` | DECIMAL(8,2) | No | Customer tip (0 if none) |
| `incentive_bonus` | DECIMAL(8,2) | No | Applicable incentive amount |
| `total_earned` | DECIMAL(8,2) | No | base_pay + tip + incentive_bonus |
| `on_time` | BOOLEAN | No | Whether delivery was within SLA |
| `customer_rating` | SMALLINT | Yes | 1-5 rating from customer |
| `distance_km` | DECIMAL(6,2) | No | Delivery distance |
| `duration_minutes` | INTEGER | No | Time from accept to deliver |
| `created_at` | TIMESTAMPTZ | No | Record creation time |

### RiderPayout

| Field | Type | Nullable | Description |
|---|---|---|---|
| `id` | UUID v4 | No | Primary key |
| `rider_id` | UUID | No | FK ? RiderProfile |
| `cycle_from` | DATE | No | Payout cycle start (Monday) |
| `cycle_to` | DATE | No | Payout cycle end (Sunday) |
| `base_earnings` | DECIMAL(12,2) | No | Total base pay for cycle |
| `incentives` | DECIMAL(12,2) | No | Incentive bonuses for cycle |
| `tips` | DECIMAL(12,2) | No | Tips for cycle |
| `streak_bonus` | DECIMAL(8,2) | No | 7-day streak bonus (0 if not earned) |
| `cod_deducted` | DECIMAL(12,2) | No | COD in hand deducted from payout |
| `net_payout` | DECIMAL(12,2) | No | Final payable amount |
| `status` | ENUM(`PENDING`,`HELD`,`RELEASED`,`FAILED`) | No | Payout lifecycle |
| `hold_reason` | TEXT | Yes | Why payout is held |
| `cashfree_transfer_id` | VARCHAR(100) | Yes | Cashfree Payouts transfer reference |
| `released_by` | UUID | Yes | FK ? AdminUser |
| `released_at` | TIMESTAMPTZ | Yes | Payout release timestamp |
| `retry_count` | SMALLINT | No | Auto-retry count (max 1) |
| `created_at` | TIMESTAMPTZ | No | Record creation (Monday morning) |

---

## Acceptance Criteria

| # | Criterion |
|---|---|
| AC-001 | Base pay for a 2 km delivery is Rs 15; base pay for a 5+ km delivery is Rs 25; a 3.5 km delivery gets the interpolated value of Rs 20. |
| AC-002 | After completing deliveries for 7 consecutive calendar days, a rider's next weekly payout includes a Rs 100 streak bonus; `daily_streak_days` resets to 0 if a full day passes without a delivery. |
| AC-003 | Weekly payout computation runs automatically on Monday morning for the previous Mon-Sun cycle. |
| AC-004 | A rider with unresolved `cod_in_hand > Rs 2,000` at payout computation time has their payout status set to `HELD`; admin_finance must release it manually. |
| AC-005 | A rider whose net_payout is < Rs 100 has the amount carried forward; no payout is disbursed and `payout_status` is set to `BELOW_THRESHOLD_CARRIED_FORWARD`. |
| AC-006 | `POST /admin/riders/:id/payout/release` triggers a Cashfree Payouts transfer; the rider receives an SMS confirmation on success. |
| AC-007 | A failed Cashfree payout is auto-retried once after 24 hours; if the retry fails, status is set to `FAILED` and an alert is sent to admin_finance. |
| AC-008 | `GET /rider/performance` shows `acceptance_rate_pct` computed as `(accepted / assigned) - 100` over the lifetime of the rider's account. |

---

## Dependencies

| Dependency | Type | Notes |
|---|---|---|
| Order Assignment (EPIC-011/STORY-003) | Internal | Trip completion triggers `RiderEarningsLedger` entry |
| COD Reconciliation (EPIC-011/STORY-007) | Internal | `cod_in_hand` deducted from payout |
| Automation Engine (EPIC-015) | Internal | Incentive rule configuration (streak thresholds, bonus amounts) |
| Cashfree Payouts | External | Payout disbursement to UPI/bank |
| Notification Service (EPIC-013) | Internal | SMS on payout success; alert on payout failure |
| Scheduled Job Runner | Internal | Monday morning payout computation cron |
| EPIC-012/STORY-004 (Rider Payouts - Finance) | Internal | Finance module view of same payout records |

---

## Notes

- Incentive rules beyond streak bonus (e.g., peak-hour bonuses, trip-count targets) are defined in the Automation Engine (EPIC-015) and are applied during payout computation; this story's `RiderEarningsLedger.incentive_bonus` stores the computed amount per trip.
- Customer ratings are averaged over all rated trips to produce `avg_rating` on RiderProfile; a minimum of 5 ratings is required before the rating is displayed publicly.
- `cancel_rate_pct` = `(assignments timed out + rejected after acceptance) / total assigned - 100`; it differs from acceptance_rate_pct.
