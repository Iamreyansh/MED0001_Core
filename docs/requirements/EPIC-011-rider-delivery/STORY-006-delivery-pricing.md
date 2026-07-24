# STORY-006: Delivery Fee Pricing

| Field | Value |
|---|---|
| Story ID | EPIC-011/STORY-006 |
| Epic | EPIC-011 - Rider Management and Delivery |
| Title | Delivery Fee Pricing |
| Status | Draft |
| Priority | P1 |
| Estimated Effort | 1 Sprint |
| Last Updated | 2026-07-24 |

---

## Overview

This story governs delivery fee calculation, simulation, and configuration. Each delivery zone has its own base fee, per-km rate, SLA, surge multiplier, and free-delivery threshold. The pricing engine calculates the delivery fee shown to customers in cart, locks the fee at order placement, and determines the corresponding rider payout component. The admin fee simulator lets the operations team model the impact of pricing changes before applying them. A customer-facing unauthenticated endpoint returns a real-time fee estimate given a pharmacy and a delivery address.

---

## User Roles

| Role | Capability |
|---|---|
| `admin_operations` | View per-zone pricing table, update pricing, run fee simulator |
| `admin_super` | All admin_operations capabilities |
| `customer` (unauthenticated) | Fetch delivery fee estimate before placing an order |

---

## Business Rules

| # | Rule |
|---|---|
| BR-001 | **Delivery fee formula:** `fee = (base_fee + distance_km - per_km_fee) - surge_multiplier`. Rounded to the nearest whole rupee. |
| BR-002 | If `order_value >= zone.free_delivery_threshold`, the delivery fee is waived (`delivery_fee = 0`). The default threshold is Rs 199. |
| BR-003 | The Rs 5 **handling fee** is charged always (including free-delivery orders) and is **not** subject to surge multiplier or waiver. Handling fee is configured at the platform level, not per zone. |
| BR-004 | The fee estimate shown to the customer in the cart is **non-binding**; the actual fee is **locked at the time of order placement** and will not change if surge changes after order creation. |
| BR-005 | **Rider delivery payout** = `max(delivery_fee_paid_by_customer - 0.70, Rs 15)`. If no delivery fee (free delivery order), the rider still receives the Rs 15 minimum platform top-up. |
| BR-006 | The admin fee simulator is a planning-only tool; it does not modify any zone config or create any order. |
| BR-007 | `GET /delivery/fee-estimate` is rate-limited to 30 requests per minute per IP to prevent abuse. |
| BR-008 | When a zone's `is_surge_active = false`, the `surge_multiplier` field is present in the response but rendered as `1.0` in the fee calculation regardless of its configured value. |

---

## API Endpoints

### GET /api/v1/admin/zones/pricing

**Auth:** `Bearer JWT` (admin_operations, admin_super)  
**Description:** Per-zone delivery fee structure table.

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "handling_fee": 5.00,
    "zones": [
      {
        "zone_id": "zone_uuid",
        "zone_name": "Koramangala",
        "city": "Bengaluru",
        "base_fee": 25.00,
        "per_km_fee": 5.00,
        "sla_minutes": 30,
        "min_order_value": 50.00,
        "free_delivery_threshold": 199.00,
        "surge_multiplier": 1.5,
        "is_surge_active": false,
        "effective_surge": 1.0,
        "sample_fee_2km": 35.00,
        "sample_fee_5km": 50.00
      }
    ]
  },
  "meta": {}
}
```

---

### POST /api/v1/admin/zones/pricing/simulate

**Auth:** `Bearer JWT` (admin_operations, admin_super)  
**Description:** Admin fee simulator - model the fee for a given scenario without creating an order.

**Request Body:**
```json
{
  "zone_id": "zone_uuid",
  "distance_km": 3.2,
  "order_value": 150.00
}
```

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "zone_id": "zone_uuid",
    "zone_name": "Koramangala",
    "input": {
      "distance_km": 3.2,
      "order_value": 150.00
    },
    "breakdown": {
      "base_fee": 25.00,
      "distance_charge": 16.00,
      "subtotal_before_surge": 41.00,
      "surge_multiplier": 1.0,
      "surge_charge": 0.00,
      "delivery_fee": 41.00,
      "handling_fee": 5.00,
      "free_delivery_waiver": false,
      "total_customer_pays": 46.00
    },
    "rider_delivery_payout": 28.70,
    "rider_payout_note": "70% of delivery_fee (Rs 41 - 0.70 = Rs 28.70); above Rs 15 minimum."
  },
  "meta": {}
}
```

**Errors:**
| Code | HTTP | Meaning |
|---|---|---|
| `ZONE_NOT_FOUND` | 404 | zone_id does not exist |
| `INVALID_DISTANCE` | 422 | distance_km must be > 0 |
| `INVALID_ORDER_VALUE` | 422 | order_value must be >= 0 |

---

### PATCH /api/v1/admin/zones/:id/pricing

**Auth:** `Bearer JWT` (admin_operations, admin_super)  
**Description:** Update delivery pricing config for a specific zone (partial update allowed).

**Request Body:**
```json
{
  "base_fee": 30.00,
  "per_km_fee": 6.00,
  "sla_minutes": 25,
  "min_order_value": 60.00,
  "free_delivery_threshold": 249.00
}
```

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "zone_id": "zone_uuid",
    "zone_name": "Koramangala",
    "base_fee": 30.00,
    "per_km_fee": 6.00,
    "sla_minutes": 25,
    "min_order_value": 60.00,
    "free_delivery_threshold": 249.00,
    "updated_by": "admin_uuid",
    "updated_at": "2026-07-24T12:00:00Z"
  },
  "meta": {}
}
```

**Errors:**
| Code | HTTP | Meaning |
|---|---|---|
| `ZONE_NOT_FOUND` | 404 | zone_id does not exist |
| `INVALID_FEE` | 422 | base_fee or per_km_fee is negative |
| `INVALID_THRESHOLD` | 422 | free_delivery_threshold < min_order_value |

---

### GET /api/v1/delivery/fee-estimate

**Auth:** None (public endpoint)  
**Description:** Customer-facing delivery fee estimate before checkout. Rate-limited at 30 req/min/IP.

**Query Params:** `?pharmacy_id=<uuid>&delivery_address_id=<uuid>` OR `?pharmacy_id=<uuid>&lat=12.9141&lng=77.6382`

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "pharmacy_id": "pharmacy_uuid",
    "pharmacy_name": "Apollo Pharmacy, Koramangala",
    "zone_id": "zone_uuid",
    "zone_name": "Koramangala",
    "distance_km": 2.4,
    "delivery_fee": 37.00,
    "handling_fee": 5.00,
    "is_free_delivery": false,
    "free_delivery_from": 199.00,
    "is_surge_active": false,
    "surge_multiplier": 1.0,
    "eta_minutes": 25,
    "sla_minutes": 30,
    "is_serviceable": true,
    "estimated_at": "2026-07-24T12:05:00Z"
  },
  "meta": {}
}
```

**Errors:**
| Code | HTTP | Meaning |
|---|---|---|
| `PHARMACY_NOT_FOUND` | 404 | pharmacy_id does not exist |
| `ADDRESS_NOT_FOUND` | 404 | delivery_address_id does not exist |
| `ADDRESS_NOT_SERVICEABLE` | 422 | Delivery address is outside all active zone polygons |
| `ZONE_OFFLINE` | 422 | Zone found but `is_serviceable = false` |
| `RATE_LIMIT_EXCEEDED` | 429 | 30 req/min/IP limit exceeded |

---

## Data Models

### ZonePricingConfig (embedded in DeliveryZone - see STORY-005)

| Field | Type | Nullable | Description |
|---|---|---|---|
| `base_fee` | DECIMAL(8,2) | No | Fixed base charge in Rs |
| `per_km_fee` | DECIMAL(8,2) | No | Per-km charge in Rs |
| `sla_minutes` | INTEGER | No | Target delivery SLA in minutes |
| `min_order_value` | DECIMAL(10,2) | No | Minimum cart value for checkout |
| `free_delivery_threshold` | DECIMAL(10,2) | No | Cart value above which delivery is free |
| `surge_multiplier` | DECIMAL(4,2) | No | Surge multiplier (1.0 = no surge) |
| `is_surge_active` | BOOLEAN | No | Whether surge is currently active |

### PlatformPricingConfig (system-wide config table)

| Field | Type | Nullable | Description |
|---|---|---|---|
| `key` | VARCHAR(100) | No | Config key (e.g., `handling_fee`) |
| `value` | TEXT | No | Config value |
| `description` | TEXT | Yes | Human-readable description |
| `updated_by` | UUID | Yes | FK ? AdminUser |
| `updated_at` | TIMESTAMPTZ | No | Last update timestamp |

### DeliveryFeeSnapshot (stored on order at placement)

| Field | Type | Nullable | Description |
|---|---|---|---|
| `order_id` | UUID | No | FK ? Order; unique |
| `zone_id` | UUID | No | Zone at time of order |
| `distance_km` | DECIMAL(6,2) | No | Distance used in calculation |
| `base_fee` | DECIMAL(8,2) | No | Base fee applied |
| `distance_charge` | DECIMAL(8,2) | No | `distance_km - per_km_fee` |
| `surge_multiplier` | DECIMAL(4,2) | No | Multiplier at placement time |
| `delivery_fee` | DECIMAL(8,2) | No | Final delivery fee (0 if free) |
| `handling_fee` | DECIMAL(8,2) | No | Handling fee applied |
| `is_free_delivery` | BOOLEAN | No | Whether delivery was free |
| `rider_payout` | DECIMAL(8,2) | No | Pre-computed rider payout |
| `created_at` | TIMESTAMPTZ | No | Snapshot timestamp (order placement) |

---

## Acceptance Criteria

| # | Criterion |
|---|---|
| AC-001 | `GET /delivery/fee-estimate` for an address 3 km from a pharmacy in Koramangala (base_fee=Rs 25, per_km=Rs 5, no surge) returns `delivery_fee = Rs 40` and `handling_fee = Rs 5`. |
| AC-002 | `GET /delivery/fee-estimate` for an order value of Rs 250 with `free_delivery_threshold = Rs 199` returns `is_free_delivery = true` and `delivery_fee = 0`. |
| AC-003 | When `is_surge_active = true` with `surge_multiplier = 1.5`, the fee-estimate returns `delivery_fee = (base + distance) - 1.5`. |
| AC-004 | The fee simulator returns a full breakdown including `rider_delivery_payout = max(delivery_fee - 0.70, 15.00)`. |
| AC-005 | `PATCH /admin/zones/:id/pricing` updates zone config; subsequent fee estimates for that zone use the new values. |
| AC-006 | The fee is locked into `DeliveryFeeSnapshot` at order placement; a subsequent surge toggle does not alter the stored fee for the order. |
| AC-007 | `GET /delivery/fee-estimate` for an address outside all zone polygons returns HTTP 422 `ADDRESS_NOT_SERVICEABLE`. |
| AC-008 | The endpoint returns HTTP 429 when the 30 req/min/IP rate limit is exceeded. |

---

## Dependencies

| Dependency | Type | Notes |
|---|---|---|
| DeliveryZone (EPIC-011/STORY-005) | Internal | Zone config is the source for pricing formula inputs |
| Google Maps Distance Matrix API | External | Distance calculation for fee estimate |
| Order Management (EPIC-010) | Internal | Fee snapshot created at order placement |
| PlatformPricingConfig | Internal | `handling_fee` system config |
| Rate Limiter (Redis-based) | Internal | 30 req/min/IP on public fee-estimate endpoint |

---

## Notes

- The distance for the fee estimate is computed from the pharmacy's stored lat/lng to the customer's saved delivery address using Google Maps Distance Matrix with `mode=driving`.
- In the cart UI, the fee estimate refreshes each time the customer changes delivery address or pharmacy; the estimate is not cached per session.
- The `DeliveryFeeSnapshot` is written atomically with the order creation transaction to ensure the locked fee is never lost.
