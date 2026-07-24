# STORY-003: Order Assignment Engine

| Field | Value |
|---|---|
| Story ID | EPIC-011/STORY-003 |
| Epic | EPIC-011 - Rider Management and Delivery |
| Title | Order Assignment Engine |
| Status | Draft |
| Priority | P0 |
| Estimated Effort | 2 Sprints |
| Last Updated | 2026-07-24 |

---

## Overview

The Order Assignment Engine handles the routing of confirmed, ready-to-pick-up orders to available delivery riders. It supports both manual dispatch (admin selects a rider from a ranked candidate list) and automated assignment (system scores all ONLINE riders using a weighted algorithm and assigns the best match). The engine tracks assignment timeouts, handles unaccepted assignments by reassigning to the next best rider, and enforces a 2-concurrent-order cap per rider. Delivery OTPs are generated at pickup time to verify rider identity at the pharmacy and confirm delivery to the customer.

---

## User Roles

| Role | Capability |
|---|---|
| `admin_operations` | View dispatch queue, manually assign, auto-assign, reassign orders |
| `admin_super` | All admin_operations capabilities |
| `rider` | View assigned/active order, accept assignment, confirm pickup, mark as delivered |

---

## Business Rules

| # | Rule |
|---|---|
| BR-001 | Only ONLINE riders with fewer than 2 concurrent active orders are eligible for assignment. |
| BR-002 | The auto-assign scoring algorithm weights: distance from pharmacy **40%**, rider rating **30%**, current order load **20%**, on_time_pct **10%**. The rider with the highest composite score is selected. |
| BR-003 | After an order is assigned, the rider has **5 minutes** to accept. If the order is not accepted within 5 minutes, it is automatically returned to the dispatch queue and reassigned to the next best eligible rider. |
| BR-004 | The auto-assign feature is controlled by a system config flag (`auto_assign_enabled`); when disabled, all assignment is manual. |
| BR-005 | The **delivery OTP** is a 4-digit numeric code generated when the order status changes to `READY_FOR_PICKUP`; it is shared with the customer via the Customer App and SMS. |
| BR-006 | The **pickup OTP** is a 4-digit code shown to the pharmacy via the Pharmacy Dashboard so the pharmacy can verify the rider is the correct person collecting the order. |
| BR-007 | Pickup confirmation (`POST /rider/orders/:id/pickup-confirm`) requires the rider to enter the OTP shown on the pharmacy screen; incorrect OTP returns `INVALID_PICKUP_OTP`. |
| BR-008 | Delivery confirmation (`POST /rider/orders/:id/deliver`) requires the rider to enter the 4-digit OTP given verbally by the customer; incorrect OTP returns `INVALID_DELIVERY_OTP`. |
| BR-009 | Reassignment requires a `reason` from the reassigning admin; the original assignment is closed with a `REASSIGNED` status and a new assignment record is created. |
| BR-010 | A rider's `concurrent_active_orders` counter is incremented on assignment acceptance and decremented on delivery completion or order cancellation. |

---

## API Endpoints

### GET /api/v1/admin/dispatch/queue

**Auth:** `Bearer JWT` (admin_operations, admin_super)  
**Description:** Unassigned orders waiting for rider assignment, sorted by wait time (oldest first).

**Query Params:** `?zone_id=<uuid>&page=1&limit=20`

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "queue": [
      {
        "order_id": "order_uuid",
        "order_number": "MED-20260724-001",
        "pharmacy_id": "pharmacy_uuid",
        "pharmacy_name": "Apollo Pharmacy, Koramangala",
        "zone_id": "zone_uuid",
        "zone_name": "Koramangala",
        "items_count": 3,
        "order_value": 450.00,
        "payment_method": "UPI",
        "created_at": "2026-07-24T09:00:00Z",
        "wait_minutes": 7,
        "recommended_rider": {
          "rider_id": "rider_uuid",
          "name": "Ravi Kumar",
          "vehicle_type": "BIKE",
          "avg_rating": 4.7,
          "distance_from_pharmacy_km": 0.8,
          "trips_today": 12,
          "concurrent_active_orders": 0,
          "composite_score": 87.4
        }
      }
    ]
  },
  "meta": {
    "page": 1,
    "limit": 20,
    "total": 14,
    "unassigned_total": 14
  }
}
```

---

### POST /api/v1/admin/dispatch/orders/:order_id/assign

**Auth:** `Bearer JWT` (admin_operations, admin_super)  
**Description:** Manually assign a specific rider to an order.

**Request Body:**
```json
{
  "rider_id": "rider_uuid"
}
```

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "assignment_id": "assignment_uuid",
    "order_id": "order_uuid",
    "rider_id": "rider_uuid",
    "rider_name": "Ravi Kumar",
    "assignment_type": "MANUAL",
    "assigned_by": "admin_uuid",
    "assigned_at": "2026-07-24T09:07:00Z",
    "accept_deadline": "2026-07-24T09:12:00Z"
  },
  "meta": {}
}
```

**Errors:**
| Code | HTTP | Meaning |
|---|---|---|
| `ORDER_NOT_FOUND` | 404 | order_id does not exist |
| `ORDER_ALREADY_ASSIGNED` | 409 | Order already has an active assignment |
| `RIDER_NOT_FOUND` | 404 | rider_id does not exist |
| `RIDER_NOT_ONLINE` | 422 | Rider is not in ONLINE status |
| `RIDER_AT_MAX_LOAD` | 422 | Rider already has 2 concurrent active orders |

---

### POST /api/v1/admin/dispatch/auto-assign-all

**Auth:** `Bearer JWT` (admin_operations, admin_super)  
**Description:** Trigger auto-assignment for all unassigned orders in the queue. Returns a summary.

**Request Body:** `{}` (empty)

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "attempted": 14,
    "assigned": 11,
    "failed_no_rider": 3,
    "assignments": [
      {
        "order_id": "order_uuid",
        "rider_id": "rider_uuid",
        "rider_name": "Ravi Kumar",
        "composite_score": 87.4
      }
    ],
    "unassigned_orders": [
      {
        "order_id": "order_uuid_2",
        "reason": "NO_ELIGIBLE_RIDER"
      }
    ]
  },
  "meta": {}
}
```

**Errors:**
| Code | HTTP | Meaning |
|---|---|---|
| `AUTO_ASSIGN_DISABLED` | 403 | `auto_assign_enabled` config flag is false |

---

### POST /api/v1/admin/dispatch/orders/:order_id/reassign

**Auth:** `Bearer JWT` (admin_operations, admin_super)  
**Description:** Reassign an already-assigned order to a different rider.

**Request Body:**
```json
{
  "rider_id": "new_rider_uuid",
  "reason": "RIDER_NO_SHOW"
}
```

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "assignment_id": "new_assignment_uuid",
    "order_id": "order_uuid",
    "previous_rider_id": "old_rider_uuid",
    "new_rider_id": "new_rider_uuid",
    "reason": "RIDER_NO_SHOW",
    "reassigned_by": "admin_uuid",
    "reassigned_at": "2026-07-24T09:15:00Z"
  },
  "meta": {}
}
```

**Errors:**
| Code | HTTP | Meaning |
|---|---|---|
| `ORDER_NOT_ASSIGNED` | 422 | Order has no active assignment to reassign |
| `RIDER_NOT_ONLINE` | 422 | New rider not ONLINE |
| `RIDER_AT_MAX_LOAD` | 422 | New rider at 2-order concurrent limit |
| `REASON_REQUIRED` | 422 | reason field missing |

---

### GET /api/v1/rider/orders/current

**Auth:** `Bearer JWT` (rider)  
**Description:** Rider's current active order details.

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "order_id": "order_uuid",
    "order_number": "MED-20260724-001",
    "assignment_id": "assignment_uuid",
    "assignment_status": "ACCEPTED",
    "pharmacy": {
      "name": "Apollo Pharmacy, Koramangala",
      "address": "100 Feet Road, Koramangala 5th Block, Bengaluru",
      "lat": 12.9352,
      "lng": 77.6245,
      "pharmacy_contact": "9900112233"
    },
    "delivery": {
      "customer_name": "Priya S",
      "address": "12, 2nd Cross, HSR Layout, Sector 2",
      "lat": 12.9141,
      "lng": 77.6382,
      "customer_contact": "9876501234"
    },
    "pickup_otp": "7821",
    "delivery_otp_hint": "Ask customer for 4-digit OTP",
    "items_count": 3,
    "payment_method": "UPI",
    "is_cod": false,
    "cod_amount": null,
    "distance_km": 2.4,
    "base_pay": 25.00
  },
  "meta": {}
}
```

---

### POST /api/v1/rider/orders/:order_id/accept

**Auth:** `Bearer JWT` (rider)  
**Description:** Rider accepts an assigned order.

**Request Body:** `{}` (empty)

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "order_id": "order_uuid",
    "assignment_id": "assignment_uuid",
    "assignment_status": "ACCEPTED",
    "accepted_at": "2026-07-24T09:08:30Z",
    "pickup_otp": "7821",
    "pharmacy_address": "100 Feet Road, Koramangala 5th Block, Bengaluru",
    "pharmacy_lat": 12.9352,
    "pharmacy_lng": 77.6245
  },
  "meta": {}
}
```

**Errors:**
| Code | HTTP | Meaning |
|---|---|---|
| `ORDER_NOT_FOUND` | 404 | order_id does not exist |
| `ASSIGNMENT_EXPIRED` | 410 | 5-minute acceptance window has elapsed |
| `NOT_YOUR_ORDER` | 403 | Order assigned to a different rider |
| `ALREADY_ACCEPTED` | 409 | Order already accepted |

---

### POST /api/v1/rider/orders/:order_id/pickup-confirm

**Auth:** `Bearer JWT` (rider)  
**Description:** Rider confirms pickup from pharmacy using the OTP displayed on the pharmacy screen.

**Request Body:**
```json
{
  "pickup_otp": "7821"
}
```

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "order_id": "order_uuid",
    "order_status": "OUT_FOR_DELIVERY",
    "pickup_confirmed_at": "2026-07-24T09:20:00Z",
    "delivery_address": "12, 2nd Cross, HSR Layout, Sector 2",
    "delivery_lat": 12.9141,
    "delivery_lng": 77.6382,
    "customer_name": "Priya S",
    "customer_contact": "9876501234"
  },
  "meta": {}
}
```

**Errors:**
| Code | HTTP | Meaning |
|---|---|---|
| `INVALID_PICKUP_OTP` | 422 | OTP does not match; remaining attempts returned |
| `ORDER_NOT_IN_READY_STATE` | 409 | Order not yet in READY_FOR_PICKUP status |
| `NOT_YOUR_ORDER` | 403 | Order assigned to different rider |

---

### POST /api/v1/rider/orders/:order_id/deliver

**Auth:** `Bearer JWT` (rider)  
**Description:** Rider marks order as delivered using OTP provided by the customer.

**Request Body:**
```json
{
  "delivery_otp_from_customer": "3942"
}
```

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "order_id": "order_uuid",
    "order_status": "DELIVERED",
    "delivered_at": "2026-07-24T09:34:00Z",
    "delivery_minutes": 14,
    "on_time": true,
    "base_pay_earned": 25.00,
    "tip_earned": 10.00,
    "total_earned_this_trip": 35.00
  },
  "meta": {}
}
```

**Errors:**
| Code | HTTP | Meaning |
|---|---|---|
| `INVALID_DELIVERY_OTP` | 422 | OTP from customer does not match |
| `ORDER_NOT_OUT_FOR_DELIVERY` | 409 | Order not in OUT_FOR_DELIVERY state |
| `NOT_YOUR_ORDER` | 403 | Order assigned to different rider |

---

## Data Models

### OrderAssignment

| Field | Type | Nullable | Description |
|---|---|---|---|
| `id` | UUID v4 | No | Primary key |
| `order_id` | UUID | No | FK ? Order |
| `rider_id` | UUID | No | FK ? RiderProfile |
| `assignment_type` | ENUM(`MANUAL`,`AUTO`) | No | How assignment was made |
| `assigned_by` | UUID | Yes | FK ? AdminUser (null = auto) |
| `status` | ENUM(`PENDING_ACCEPTANCE`,`ACCEPTED`,`PICKED_UP`,`DELIVERED`,`REASSIGNED`,`TIMED_OUT`,`CANCELLED`) | No | Assignment lifecycle |
| `accept_deadline` | TIMESTAMPTZ | No | 5 minutes after assignment |
| `accepted_at` | TIMESTAMPTZ | Yes | When rider accepted |
| `pickup_confirmed_at` | TIMESTAMPTZ | Yes | When pickup OTP verified |
| `delivered_at` | TIMESTAMPTZ | Yes | When delivery OTP verified |
| `pickup_otp` | VARCHAR(4) | No | OTP for pharmacy verification |
| `delivery_otp` | VARCHAR(4) | No | OTP for customer verification |
| `reassign_reason` | ENUM(`RIDER_NO_SHOW`,`RIDER_OFFLINE`,`PERFORMANCE`,`OTHER`) | Yes | Reason if reassigned |
| `composite_score` | DECIMAL(6,2) | Yes | Score used by auto-assign |
| `created_at` | TIMESTAMPTZ | No | Assignment creation time |
| `updated_at` | TIMESTAMPTZ | No | Last update |

---

## Acceptance Criteria

| # | Criterion |
|---|---|
| AC-001 | Auto-assign selects the rider with the highest composite score (distance 40%, rating 30%, load 20%, on_time 10%) from eligible ONLINE riders with < 2 active orders. |
| AC-002 | A rider who does not accept within 5 minutes has the assignment status set to `TIMED_OUT` and the order is returned to the dispatch queue. |
| AC-003 | `POST /rider/orders/:id/accept` with an expired assignment (past 5-minute window) returns HTTP 410 `ASSIGNMENT_EXPIRED`. |
| AC-004 | `POST /rider/orders/:id/pickup-confirm` with a correct pickup OTP advances the order to `OUT_FOR_DELIVERY`; incorrect OTP returns `INVALID_PICKUP_OTP`. |
| AC-005 | `POST /rider/orders/:id/deliver` with a correct delivery OTP advances the order to `DELIVERED` and computes `on_time` flag; earnings entry is created. |
| AC-006 | A rider with 2 active assignments cannot be assigned a 3rd order via either manual or auto-assign; returns `RIDER_AT_MAX_LOAD`. |
| AC-007 | When `auto_assign_enabled = false`, `POST /admin/dispatch/auto-assign-all` returns HTTP 403 `AUTO_ASSIGN_DISABLED`. |
| AC-008 | Reassignment logs the previous rider, new rider, and reason in `OrderAssignment`; previous assignment record is closed with `REASSIGNED` status. |

---

## Dependencies

| Dependency | Type | Notes |
|---|---|---|
| Order Management (EPIC-010) | Internal | Orders enter dispatch queue when status = `READY_FOR_PICKUP` |
| Rider Status (EPIC-011/STORY-002) | Internal | Only ONLINE riders with < 2 active orders are eligible |
| Google Maps Distance Matrix API | External | Distance from rider to pharmacy for scoring |
| Redis | External | Assignment queue, OTP storage (short TTL), concurrent order counter |
| Notification Service (EPIC-013) | Internal | Push notification to rider on assignment; timeout alerts to admin |
| Config Service | Internal | `auto_assign_enabled` feature flag |

---

## Notes

- OTPs are 4-digit random integers generated using a cryptographically secure RNG; stored in Redis with a 30-minute TTL.
- The pickup OTP is shown on the Pharmacy Dashboard screen when order status is `READY_FOR_PICKUP`; it is **not** sent to the rider via push (rider enters it by reading the pharmacy screen).
- The delivery OTP is sent to the customer via SMS and shown in the Customer App when order status is `OUT_FOR_DELIVERY`; the rider reads it verbally from the customer.
- In rare cases where OTP delivery fails (customer claims not to have received it), admin can mark the order as delivered manually via the order management screen (with photo evidence from rider).
- The composite score for auto-assign is logged in `OrderAssignment.composite_score` for audit and analytics purposes.
