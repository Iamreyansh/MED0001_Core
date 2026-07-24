# STORY-007: COD Reconciliation (Rider Side)

| Field | Value |
|---|---|
| Story ID | EPIC-011/STORY-007 |
| Epic | EPIC-011 - Rider Management and Delivery |
| Title | COD Reconciliation (Rider Side) |
| Status | Draft |
| Priority | P1 |
| Estimated Effort | 1 Sprint |
| Last Updated | 2026-07-24 |

---

## Overview

This story covers the rider-facing and admin operations view of COD (Cash on Delivery) cash management. Riders collect cash from customers on COD orders and are required to deposit it to the platform periodically. The system tracks how much cash each rider holds, enforces a configurable float limit (default Rs 2,000), auto-flags riders exceeding the limit as `FLOAT_RISK`, and blocks them from accepting new COD orders until resolved. The admin operations team can view the COD reconciliation board, mark deposits as received, and send reminders. The finance-side COD float management story (EPIC-012/STORY-006) covers the platform-level accounting view.

---

## User Roles

| Role | Capability |
|---|---|
| `rider` | View own COD summary, submit deposit request |
| `admin_operations` | View COD reconciliation board, mark deposits, send reminders |
| `admin_finance` | Mark deposits confirmed, view float risk summary |
| `admin_super` | All of the above |

---

## Business Rules

| # | Rule |
|---|---|
| BR-001 | The COD float limit per rider is **Rs 2,000** by default; this is configurable in system settings per zone or globally. |
| BR-002 | When a rider's `cod_in_hand` exceeds the limit, the rider is auto-flagged as `FLOAT_RISK`; an automated reminder push notification and SMS are sent immediately. |
| BR-003 | A rider with `cod_in_hand > cod_float_limit` cannot accept **new COD orders**; they can still accept UPI/card orders. |
| BR-004 | A rider's deposit request (`POST /rider/cod/deposit-request`) records the claimed deposit amount, mode, and reference; it is **not** automatically confirmed - admin must acknowledge with `POST /admin/finance/cod/:rider_id/mark-deposited`. |
| BR-005 | COD amount collected per delivery is tracked in `CODCollection`; the total `cod_in_hand` on `RiderProfile` is the sum of unconfirmed deposits. |
| BR-006 | COD amount is deducted from the rider's net payout in the weekly settlement cycle (since the rider already holds the cash, payout is reduced by that amount). |
| BR-007 | A **daily COD reconciliation report** is auto-generated at 11 PM and emailed to admin_finance. |
| BR-008 | Float risk riders are displayed with a red status indicator in the fleet board (EPIC-011/STORY-002). |

---

## API Endpoints

### GET /api/v1/admin/finance/cod

**Auth:** `Bearer JWT` (admin_operations, admin_finance, admin_super)  
**Description:** COD reconciliation overview - summary chips and rider-level table.

**Query Params:** `?zone_id=<uuid>&risk_only=true&page=1&limit=20`

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "summary": {
      "total_cod_in_hand": 28450.00,
      "deposited_today": 15200.00,
      "pending_deposit": 13250.00,
      "float_risk_riders_count": 4,
      "cod_float_limit": 2000.00
    },
    "riders": [
      {
        "rider_id": "rider_uuid",
        "rider_name": "Ravi Kumar",
        "zone_name": "Koramangala",
        "trips_today": 12,
        "cod_collected": 2850.00,
        "cod_deposited": 1000.00,
        "cod_in_hand": 1850.00,
        "deposit_status": "PARTIAL",
        "risk_status": "SAFE",
        "last_deposit_at": "2026-07-24T14:00:00Z"
      },
      {
        "rider_id": "rider_uuid_2",
        "rider_name": "Suresh M",
        "zone_name": "Indiranagar",
        "trips_today": 15,
        "cod_collected": 3600.00,
        "cod_deposited": 0.00,
        "cod_in_hand": 3600.00,
        "deposit_status": "PENDING",
        "risk_status": "FLOAT_RISK",
        "last_deposit_at": null
      }
    ]
  },
  "meta": {
    "page": 1,
    "limit": 20,
    "total": 28
  }
}
```

---

### POST /api/v1/admin/finance/cod/:rider_id/mark-deposited

**Auth:** `Bearer JWT` (admin_finance, admin_super)  
**Description:** Admin confirms a COD deposit from a rider.

**Request Body:**
```json
{
  "amount": 2000.00,
  "deposited_at": "2026-07-24T15:00:00Z",
  "reference_number": "UPI-REF-20260724-001",
  "notes": "Deposit received via UPI to platform account."
}
```

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "rider_id": "rider_uuid",
    "deposit_id": "deposit_uuid",
    "amount_confirmed": 2000.00,
    "cod_in_hand_after": 600.00,
    "risk_status_after": "SAFE",
    "confirmed_by": "admin_uuid",
    "confirmed_at": "2026-07-24T15:05:00Z"
  },
  "meta": {}
}
```

**Errors:**
| Code | HTTP | Meaning |
|---|---|---|
| `RIDER_NOT_FOUND` | 404 | rider_id does not exist |
| `AMOUNT_EXCEEDS_IN_HAND` | 422 | Deposit amount > rider's current cod_in_hand |
| `INVALID_AMOUNT` | 422 | Amount must be > 0 |

---

### GET /api/v1/rider/cod

**Auth:** `Bearer JWT` (rider)  
**Description:** Rider's own COD summary - balance, limits, and deposit history.

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "rider_id": "rider_uuid",
    "collected_today": 2850.00,
    "deposited_today": 1000.00,
    "in_hand": 1850.00,
    "limit": 2000.00,
    "limit_remaining": 150.00,
    "risk_status": "SAFE",
    "can_accept_cod_orders": true,
    "next_deposit_reminder_at": "2026-07-24T20:00:00Z",
    "recent_cod_trips": [
      {
        "order_id": "order_uuid",
        "order_number": "MED-20260724-015",
        "cod_amount": 350.00,
        "collected_at": "2026-07-24T13:45:00Z",
        "deposited": false
      }
    ]
  },
  "meta": {}
}
```

---

### POST /api/v1/rider/cod/deposit-request

**Auth:** `Bearer JWT` (rider)  
**Description:** Rider submits a deposit claim for admin acknowledgement.

**Request Body:**
```json
{
  "amount": 2000.00,
  "deposit_mode": "UPI",
  "reference_number": "UPI-REF-20260724-001",
  "notes": "Transferred to platform UPI ID."
}
```

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "deposit_request_id": "deposit_req_uuid",
    "rider_id": "rider_uuid",
    "amount": 2000.00,
    "deposit_mode": "UPI",
    "reference_number": "UPI-REF-20260724-001",
    "status": "PENDING_CONFIRMATION",
    "submitted_at": "2026-07-24T15:00:00Z",
    "message": "Your deposit request has been submitted. Admin will confirm within 2 hours."
  },
  "meta": {}
}
```

**Errors:**
| Code | HTTP | Meaning |
|---|---|---|
| `AMOUNT_EXCEEDS_IN_HAND` | 422 | Claimed deposit > cod_in_hand |
| `DUPLICATE_REFERENCE` | 409 | Reference number already submitted |
| `INVALID_DEPOSIT_MODE` | 422 | deposit_mode not BRANCH or UPI |

---

### POST /api/v1/admin/finance/cod/:rider_id/remind

**Auth:** `Bearer JWT` (admin_operations, admin_finance, admin_super)  
**Description:** Send a deposit reminder to a rider via push notification and SMS.

**Request Body:**
```json
{
  "message": "You have Rs 2,850 in COD cash. Please deposit today to avoid order restrictions."
}
```

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "rider_id": "rider_uuid",
    "notification_sent": true,
    "sms_sent": true,
    "sent_by": "admin_uuid",
    "sent_at": "2026-07-24T15:10:00Z"
  },
  "meta": {}
}
```

---

## Data Models

### CODCollection

| Field | Type | Nullable | Description |
|---|---|---|---|
| `id` | UUID v4 | No | Primary key |
| `rider_id` | UUID | No | FK ? RiderProfile |
| `order_id` | UUID | No | FK ? Order |
| `cod_amount` | DECIMAL(10,2) | No | Cash collected for this order |
| `collected_at` | TIMESTAMPTZ | No | Delivery completion timestamp |
| `deposit_id` | UUID | Yes | FK ? CODDeposit; null until deposited |
| `is_deposited` | BOOLEAN | No | Whether this amount has been deposited |
| `created_at` | TIMESTAMPTZ | No | Record creation |

### CODDeposit

| Field | Type | Nullable | Description |
|---|---|---|---|
| `id` | UUID v4 | No | Primary key |
| `rider_id` | UUID | No | FK ? RiderProfile |
| `amount` | DECIMAL(10,2) | No | Deposit amount claimed/confirmed |
| `deposit_mode` | ENUM(`BRANCH`,`UPI`) | No | How the deposit was made |
| `reference_number` | VARCHAR(100) | No | UPI transaction ID or branch receipt |
| `status` | ENUM(`PENDING_CONFIRMATION`,`CONFIRMED`,`REJECTED`) | No | Deposit lifecycle |
| `submitted_at` | TIMESTAMPTZ | No | When rider submitted the request |
| `confirmed_at` | TIMESTAMPTZ | Yes | When admin confirmed |
| `confirmed_by` | UUID | Yes | FK ? AdminUser |
| `notes` | TEXT | Yes | Admin or rider notes |

---

## Acceptance Criteria

| # | Criterion |
|---|---|
| AC-001 | When a rider's `cod_in_hand` exceeds Rs 2,000, the rider is automatically flagged as `FLOAT_RISK`; they receive a push notification and SMS immediately. |
| AC-002 | A `FLOAT_RISK` rider attempting to accept a new COD order receives an error `COD_LIMIT_EXCEEDED`; they can still accept non-COD orders. |
| AC-003 | Admin marking a deposit confirms the amount, reduces rider's `cod_in_hand` accordingly, and clears `FLOAT_RISK` status if `cod_in_hand` is now below the limit. |
| AC-004 | Rider's `GET /rider/cod` accurately shows `in_hand`, `limit_remaining`, and `can_accept_cod_orders = false` when at or above the limit. |
| AC-005 | A deposit request submitted with a reference number already in the system returns HTTP 409 `DUPLICATE_REFERENCE`. |
| AC-006 | The admin COD board filters `risk_only=true` to show only FLOAT_RISK riders, sorted by `cod_in_hand` descending. |
| AC-007 | The daily COD reconciliation report is generated at 11 PM and is available in the finance module (EPIC-012/STORY-006); admin_finance receives an email notification. |

---

## Dependencies

| Dependency | Type | Notes |
|---|---|---|
| Order Management (EPIC-010) | Internal | COD order delivery creates `CODCollection` record |
| RiderProfile (EPIC-011/STORY-001) | Internal | `cod_in_hand` counter maintained on RiderProfile |
| EPIC-012/STORY-006 (COD Float - Finance) | Internal | Finance-side view of same COD data |
| EPIC-012/STORY-004 (Rider Payouts) | Internal | COD in_hand deducted from weekly payout |
| Notification Service (EPIC-013) | Internal | FLOAT_RISK auto-reminder push + SMS |
| Scheduled Job Runner | Internal | Daily 11 PM reconciliation report generation |

---

## Notes

- The `cod_in_hand` field on `RiderProfile` is updated atomically whenever a COD delivery is completed (incremented) or a deposit is confirmed (decremented).
- The system does not auto-reconcile deposit requests - admin confirmation is always required to prevent fraud.
- Configurable COD float limit is stored in `PlatformPricingConfig` with key `cod_float_limit_default` and per-zone overrides in `DeliveryZone.cod_float_limit_override` (if needed in v2).
