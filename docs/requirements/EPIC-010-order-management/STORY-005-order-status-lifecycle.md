# STORY-005: Order Status Lifecycle

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-005 |
| **Epic** | EPIC-010 - Order Management |
| **Priority** | P0 |
| **Complexity** | L |
| **Status** | Draft |

---

## Overview

This story defines the complete order state machine - from the moment a pharmacy receives a new order (PENDING_ACCEPTANCE) through packing, pickup, delivery, and all rejection/cancellation paths. Pharmacies advance orders through the packing and dispatch stages, riders are assigned, a delivery OTP is generated for secure handoff, and customers see live tracking with ETA countdown. Admins can force-advance or override order status at any stage. A 30-minute SLA is tracked from confirmation to delivery, with breaches surfaced in the admin live feed.

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| `customer` | Read-only | View tracking, full timeline |
| `pharmacy_owner` | Advance status | Accept, reject, advance to READY_FOR_PICKUP |
| `pharmacy_staff` | Advance status | Same as pharmacy_owner |
| `rider` | OUT_FOR_DELIVERY / DELIVERED | Advance delivery steps |
| `admin_super` | Full override | Force any transition, reassign, cancel |
| `admin_operations` | Full override | Same as admin_super for order status |

---

## Business Rules

1. **State machine:** The canonical order lifecycle is: `PENDING_ACCEPTANCE ? ACCEPTED ? PACKING ? READY_FOR_PICKUP ? OUT_FOR_DELIVERY ? DELIVERED`. Terminal states: `DELIVERED`, `CANCELLED`. Admins can force any non-terminal transition. Customer and pharmacy can advance only within their permitted transitions.
2. **10-minute pharmacy acceptance window:** A pharmacy must accept the order within 10 minutes of `confirmed_at`. If not accepted within this window, the order is auto-cancelled by the rules engine with reason `PHARMACY_ACCEPTANCE_TIMEOUT` and a refund is triggered.
3. **Pharmacy rejection triggers refund:** If a pharmacy calls `POST /api/v1/pharmacy/orders/:order_id/reject`, the order is auto-cancelled and a full refund is initiated immediately (online ? source account; COD ? no refund needed pre-delivery; wallet ? wallet).
4. **Delivery OTP:** A 4-digit numeric OTP is generated when the order transitions to `READY_FOR_PICKUP`. It is sent to the customer via SMS/push and is required for the rider to confirm delivery. OTP is stored hashed and validated at delivery confirmation.
5. **SLA tracking:** SLA = 30 minutes from `confirmed_at` to `DELIVERED`. SLA breach is recorded on the order when the 30-minute window passes without reaching `DELIVERED`. `SLA_RISK` = less than 5 minutes remaining on SLA while still in progress.
6. **Rider assignment:** In pharmacy dispatch mode, the pharmacy assigns a rider via `POST /api/v1/pharmacy/orders/:order_id/assign-rider`. If no rider is assigned within 30 minutes of `READY_FOR_PICKUP`, an auto-escalation alert fires to admin.
7. **Status event immutability:** Every status transition is recorded in `OrderStatusEvent` with a timestamp and actor. These events are never modified and form the full order timeline.
8. **Customer tracking polling:** The tracking endpoint (`GET /api/v1/orders/:order_id/tracking`) is designed for 10-second client polling. It returns a `last_updated_at` for client-side cache invalidation.

---

## API Endpoints

### 1. Pharmacy - Accept Order

```POST /api/v1/pharmacy/orders/:order_id/accept```

**Authentication:** Bearer JWT - `pharmacy_owner` | `pharmacy_staff`
**Rate Limit:** 30 req/min

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "order_id": "ord_01J3KP7VDEF789",
    "order_number": "ORD-20260724-00123",
    "status": "ACCEPTED",
    "accepted_at": "2026-07-24T11:35:00Z"
  }
}
```

**Errors:**

| Code | HTTP | Description |
|------|------|-------------|
| `ORDER_NOT_FOUND` | 404 | Order not in this pharmacy's queue |
| `ORDER_ACCEPTANCE_TIMEOUT` | 409 | 10-minute window has elapsed |
| `ORDER_ALREADY_ACTIONED` | 409 | Order already accepted or rejected |

---

### 2. Pharmacy - Advance Order Status

```PATCH /api/v1/pharmacy/orders/:order_id/status```

**Authentication:** Bearer JWT - `pharmacy_owner` | `pharmacy_staff`
**Rate Limit:** 20 req/min

**Request Body:**
```json
{
  "status": "PACKING",
  "notes": "Started packing"
}
```

**Valid pharmacy transitions:** `ACCEPTED ? PACKING ? READY_FOR_PICKUP`

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "order_id": "ord_01J3KP7VDEF789",
    "status": "PACKING",
    "updated_at": "2026-07-24T11:37:00Z"
  }
}
```

**Errors:**

| Code | HTTP | Description |
|------|------|-------------|
| `INVALID_STATUS_TRANSITION` | 422 | Transition not allowed for pharmacy role |

---

### 3. Pharmacy - Reject Order

```POST /api/v1/pharmacy/orders/:order_id/reject```

**Authentication:** Bearer JWT - `pharmacy_owner` | `pharmacy_staff`
**Rate Limit:** 10 req/min

**Request Body:**
```json
{
  "reason": "OUT_OF_STOCK",
  "message": "Metformin 500mg unavailable. Please reorder from another pharmacy."
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `reason` | ENUM | Yes | `OUT_OF_STOCK`, `CLOSING_SOON`, `CANNOT_FULFIL`, `OTHER` |
| `message` | string | No | Custom message for customer (max 300 chars) |

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "order_id": "ord_01J3KP7VDEF789",
    "status": "CANCELLED",
    "refund_initiated": true,
    "refund_amount": 221.25,
    "refund_to": "SOURCE"
  }
}
```

---

### 4. Pharmacy - Assign Rider

```POST /api/v1/pharmacy/orders/:order_id/assign-rider```

**Authentication:** Bearer JWT - `pharmacy_owner` | `pharmacy_staff`
**Rate Limit:** 10 req/min

**Request Body:**
```json
{ "rider_id": "rider_01J3KP7VUUU111" }
```

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "order_id": "ord_01J3KP7VDEF789",
    "rider_id": "rider_01J3KP7VUUU111",
    "rider_name": "Suresh Kumar",
    "rider_phone": "+91-9988776655",
    "rider_vehicle_plate": "KA01AB1234",
    "assigned_at": "2026-07-24T11:42:00Z"
  }
}
```

---

### 5. Admin - Force-Advance Order Status

```PATCH /api/v1/admin/orders/:order_id/status```

**Authentication:** Bearer JWT - `admin_super` | `admin_operations`
**Rate Limit:** 10 req/min

**Request Body:**
```json
{
  "status": "OUT_FOR_DELIVERY",
  "reason": "Rider confirmed pickup; pharmacy dashboard unresponsive",
  "notes": "Manual advance by ops team"
}
```

**Response `200 OK`:** Returns updated order status with `advanced_by` and `advanced_at`.

---

### 6. Get Order Tracking

```GET /api/v1/orders/:order_id/tracking```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 60 req/min (designed for frequent polling)

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "order_id": "ord_01J3KP7VDEF789",
    "order_number": "ORD-20260724-00123",
    "status": "OUT_FOR_DELIVERY",
    "current_step": "Rider on the way",
    "steps": [
      { "name": "Order Confirmed", "completed": true, "timestamp": "2026-07-24T11:31:00Z" },
      { "name": "Accepted by Pharmacy", "completed": true, "timestamp": "2026-07-24T11:35:00Z" },
      { "name": "Being Packed", "completed": true, "timestamp": "2026-07-24T11:37:00Z" },
      { "name": "Ready for Pickup", "completed": true, "timestamp": "2026-07-24T11:42:00Z" },
      { "name": "Out for Delivery", "completed": true, "timestamp": "2026-07-24T11:45:00Z" },
      { "name": "Delivered", "completed": false, "timestamp": null }
    ],
    "rider": {
      "name": "Suresh Kumar",
      "avatar": "https://cdn.nammamedmate.com/riders/suresh.jpg",
      "vehicle_plate": "KA01AB1234",
      "phone": "+91-9988776655"
    },
    "eta_minutes": 7,
    "sla_remaining_minutes": 8,
    "last_updated_at": "2026-07-24T11:45:00Z"
  }
}
```

---

### 7. Get Order Timeline

```GET /api/v1/orders/:order_id/timeline```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 30 req/min

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "order_id": "ord_01J3KP7VDEF789",
    "events": [
      { "status": "PENDING_ACCEPTANCE", "timestamp": "2026-07-24T11:30:00Z", "actor": "system", "notes": null },
      { "status": "ACCEPTED", "timestamp": "2026-07-24T11:35:00Z", "actor": "pharmacy", "notes": null },
      { "status": "PACKING", "timestamp": "2026-07-24T11:37:00Z", "actor": "pharmacy", "notes": "Started packing" },
      { "status": "READY_FOR_PICKUP", "timestamp": "2026-07-24T11:42:00Z", "actor": "pharmacy", "notes": null },
      { "status": "OUT_FOR_DELIVERY", "timestamp": "2026-07-24T11:45:00Z", "actor": "rider", "notes": null }
    ]
  }
}
```

---

## Data Models

### OrderStatusEvent

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID v4 | PK, NOT NULL | Event identifier |
| `order_id` | UUID | FK ? orders.id, NOT NULL | Parent order |
| `from_status` | ENUM | NOT NULL | Previous status |
| `to_status` | ENUM | NOT NULL | New status |
| `actor_type` | ENUM | NOT NULL | `SYSTEM`, `CUSTOMER`, `PHARMACY`, `RIDER`, `ADMIN` |
| `actor_id` | UUID | nullable | User ID of actor |
| `notes` | string | nullable | Transition notes |
| `created_at` | timestamp | NOT NULL | Immutable event timestamp |

### Order State Machine (additions)

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `status` | ENUM | NOT NULL | `PAYMENT_PENDING`, `PENDING_ACCEPTANCE`, `ACCEPTED`, `PACKING`, `READY_FOR_PICKUP`, `OUT_FOR_DELIVERY`, `DELIVERED`, `CANCELLED` |
| `delivery_otp` | string(4) | nullable, hashed | Generated at READY_FOR_PICKUP |
| `otp_verified_at` | timestamp | nullable | When OTP was validated |
| `sla_deadline` | timestamp | nullable | `confirmed_at + 30 min` |
| `sla_breached` | boolean | default false | Set if DELIVERED after sla_deadline |
| `rider_assigned_at` | timestamp | nullable | When rider was assigned |
| `accepted_at` | timestamp | nullable | Pharmacy acceptance time |
| `delivered_at` | timestamp | nullable | Delivery confirmation time |

---

## Acceptance Criteria

- [ ] **Given** a pharmacy does not accept an order within 10 minutes, **when** the auto-cancel job runs, **then** the order transitions to `CANCELLED` with `reason: PHARMACY_ACCEPTANCE_TIMEOUT` and a full refund is triggered.
- [ ] **Given** a pharmacy rejects an order in `PENDING_ACCEPTANCE` status, **when** the rejection is saved, **then** the order is `CANCELLED`, the customer receives a WhatsApp notification with the rejection reason, and a full refund is initiated.
- [ ] **Given** an order transitions to `READY_FOR_PICKUP`, **when** the transition succeeds, **then** a 4-digit OTP is generated, stored (hashed), and sent to the customer via SMS/push.
- [ ] **Given** an order's `sla_deadline` is 3 minutes away and it is still in `OUT_FOR_DELIVERY`, **when** the admin live feed is polled, **then** the order appears with `SLA_RISK = true`.
- [ ] **Given** a pharmacy tries to advance an order from `PACKING` directly to `OUT_FOR_DELIVERY`, **when** the status PATCH is submitted, **then** the API returns HTTP 422 with `INVALID_STATUS_TRANSITION`.
- [ ] **Given** `GET /api/v1/orders/:order_id/tracking` is called while the order is `OUT_FOR_DELIVERY`, **when** the response is returned, **then** `steps[4].completed = true` and `steps[5].completed = false`, and `eta_minutes` is populated.
- [ ] **Given** an order transitions through all states to `DELIVERED`, **when** `GET /api/v1/orders/:order_id/timeline` is called, **then** all 6 events are listed with non-null timestamps in chronological order.
- [ ] **Given** no rider is assigned within 30 minutes of `READY_FOR_PICKUP`, **when** the escalation job runs, **then** an alert is sent to admin_operations.

---

## Dependencies

| Dependency | Story / System | Notes |
|------------|---------------|-------|
| EPIC-010 STORY-004 - Order placement | Upstream | Orders enter state machine after creation |
| EPIC-010 STORY-006 - Cancellation/refund | Downstream | Rejection triggers cancellation logic |
| EPIC-011 - Rider dispatch | Downstream | Rider assignment and delivery tracking |
| Notification service (SMS + WhatsApp + Push) | Platform | OTP delivery, status updates |
| Rules engine (EPIC - Automation) | Downstream | Auto-cancel, SLA breach alerts |

---

## Notes

- OTP must be 4-digit numeric (`String.format("%04d", random(0, 9999))`), stored as `bcrypt` hash or equivalent. The plaintext OTP is sent via SMS/push only at generation time and is never stored in plaintext.
- The `sla_remaining_minutes` in the tracking endpoint is a real-time computation: `CEIL((sla_deadline - NOW()) / 60)`. It can be negative (breach) and should be shown to admin but clamped to 0 for the customer.
- `delivery_otp` on the order is cleared (set to null) after successful OTP verification to prevent reuse.
