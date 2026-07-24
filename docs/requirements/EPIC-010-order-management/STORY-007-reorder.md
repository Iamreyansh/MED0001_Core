# STORY-007: Customer Reorder

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-007 |
| **Epic** | EPIC-010 - Order Management |
| **Priority** | P1 |
| **Complexity** | S |
| **Status** | Draft |

---

## Overview

This story provides the reorder feature that allows customers to quickly re-purchase medicines from a previous order. When a customer taps "Reorder", the system attempts to pre-fill a new cart with the same items and quantities from the historical order at the same pharmacy. If the original pharmacy is closed, suspended, or out of stock, the engine falls back to the smart-select algorithm to find the nearest pharmacy stocking the most items. Unavailable medicines are excluded from the cart with a clear per-item message. The story also defines the order history and active orders list endpoints used by the customer app.

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| `customer` | Full access | Reorder, view history, view active orders |
| `admin_support` | Read-only | View order history for dispute support |

---

## Business Rules

1. **Same-pharmacy priority:** Reorder first checks if the original pharmacy is `is_active`, `is_online`, and has all items in stock. If all conditions are met, the cart is locked to the original pharmacy.
2. **Fallback to smart-select:** If the original pharmacy is unavailable or out of stock for any item, the smart-select algorithm runs for each missing item, and the pharmacy with the highest fill coverage (most items available) is selected as the fallback.
3. **Partial reorder cart:** Items not available at any pharmacy within 5km are excluded from the reorder cart. The response includes an `excluded_items` list with a `reason` for each exclusion (`OUT_OF_STOCK`, `PHARMACY_UNAVAILABLE`).
4. **Prescription not re-attached:** Reorder does NOT automatically re-attach the original prescription. If the reorder cart contains Rx-only items, the customer must manually attach a valid prescription before checkout.
5. **No coupon pre-applied:** The reorder creates a fresh cart with no coupon applied. The customer can apply a coupon manually.
6. **Fresh cart creation:** Reorder always creates a brand-new cart. If the customer already has an active cart, the reorder prompts a confirmation (handled client-side with `confirm_cart_clear: true` in the API request).
7. **`confirm_pharmacy_change`:** If the original pharmacy is unavailable and the fallback pharmacy is different, the API requires `confirm_pharmacy_change: true`. Without it, the API returns `PHARMACY_CHANGE_REQUIRED` with the suggested fallback pharmacy.
8. **Only DELIVERED and CANCELLED orders in history:** `GET /api/v1/orders/history` returns all orders with terminal status (`DELIVERED` or `CANCELLED`) in descending order of `created_at`.

---

## API Endpoints

### 1. Reorder

```POST /api/v1/orders/:past_order_id/reorder```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 10 req/min

**Request Body:**
```json
{
  "confirm_pharmacy_change": false
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `confirm_pharmacy_change` | boolean | No (default false) | Required to proceed when fallback pharmacy is different |

**Response `200 OK` (same pharmacy, all items available):**
```json
{
  "success": true,
  "data": {
    "cart_id": "cart_01J3KP7VUUU000",
    "pharmacy": {
      "id": "ph_01J3KP7VFFF666",
      "name": "Sai Medicals",
      "area": "Koramangala"
    },
    "items_added": [
      { "name": "Metformin 500mg", "quantity": 3, "price": 85.00 }
    ],
    "excluded_items": [],
    "prescription_required": true,
    "prescription_attached": false,
    "message": "Cart created with 1 item. Please attach a prescription to proceed."
  }
}
```

**Response `200 OK` (fallback pharmacy, some items excluded):**
```json
{
  "success": true,
  "data": {
    "cart_id": "cart_01J3KP7VUUU000",
    "pharmacy": {
      "id": "ph_01J3KP7VQQQ777",
      "name": "Apollo Pharmacy",
      "area": "BTM Layout",
      "note": "Original pharmacy Sai Medicals is currently closed"
    },
    "items_added": [
      { "name": "Metformin 500mg", "quantity": 3, "price": 90.00 }
    ],
    "excluded_items": [
      { "name": "Glipizide 5mg", "quantity": 30, "reason": "OUT_OF_STOCK", "message": "Not available at nearby pharmacies" }
    ],
    "prescription_required": true,
    "prescription_attached": false,
    "message": "Cart created with 1 item. 1 item was unavailable and excluded."
  }
}
```

**Response `409 PHARMACY_CHANGE_REQUIRED` (when confirm_pharmacy_change not set):**
```json
{
  "success": false,
  "error": {
    "code": "PHARMACY_CHANGE_REQUIRED",
    "message": "Original pharmacy Sai Medicals is currently closed. Cart will be created at Apollo Pharmacy, BTM Layout.",
    "suggested_pharmacy": {
      "id": "ph_01J3KP7VQQQ777",
      "name": "Apollo Pharmacy",
      "area": "BTM Layout"
    }
  }
}
```

**Errors:**

| Code | HTTP | Description |
|------|------|-------------|
| `ORDER_NOT_FOUND` | 404 | Past order not found for this customer |
| `NO_ITEMS_AVAILABLE` | 422 | All items from the original order are unavailable |
| `PHARMACY_CHANGE_REQUIRED` | 409 | Fallback pharmacy differs; requires confirm_pharmacy_change: true |

---

### 2. Order History

```GET /api/v1/orders/history```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 30 req/min

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `page` | integer | 1 | Pagination |
| `limit` | integer | 20 | Items per page (max 50) |
| `status` | string | `ALL` | `DELIVERED`, `CANCELLED`, `ALL` |

**Response `200 OK`:**
```json
{
  "success": true,
  "data": [
    {
      "order_id": "ord_01J3KP7VDEF789",
      "order_number": "ORD-20260724-00123",
      "pharmacy": {
        "name": "Sai Medicals",
        "logo": "https://cdn.nammamedmate.com/pharmacies/sai-medicals.png"
      },
      "items_count": 1,
      "items_preview": ["Metformin 500mg - 3"],
      "status": "DELIVERED",
      "total": 221.25,
      "payment_method": "UPI",
      "has_rx_items": true,
      "created_at": "2026-07-24T11:30:00Z",
      "delivered_at": "2026-07-24T11:55:00Z"
    }
  ],
  "meta": {
    "page": 1,
    "limit": 20,
    "total": 1,
    "total_pages": 1
  }
}
```

---

### 3. Active Orders

```GET /api/v1/orders/active```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 60 req/min

**Response `200 OK`:**
```json
{
  "success": true,
  "data": [
    {
      "order_id": "ord_01J3KP7VDEF789",
      "order_number": "ORD-20260724-00123",
      "pharmacy": { "name": "Sai Medicals" },
      "status": "OUT_FOR_DELIVERY",
      "items_count": 1,
      "total": 221.25,
      "eta_minutes": 7,
      "rider": {
        "name": "Suresh Kumar",
        "phone": "+91-9988776655",
        "vehicle_plate": "KA01AB1234"
      },
      "created_at": "2026-07-24T11:30:00Z",
      "estimated_delivery_at": "2026-07-24T11:52:00Z"
    }
  ]
}
```

---

## Data Models

No new models for this story - reorder creates a `Cart` (STORY-001 model) with items from the original `Order`.

### ReorderAttemptLog (for analytics, optional)

| Field | Type | Description |
|-------|------|-------------|
| `id` | UUID | Log entry |
| `customer_id` | UUID | Customer |
| `source_order_id` | UUID | Original order |
| `resulting_cart_id` | UUID | New cart created |
| `pharmacy_changed` | boolean | Whether fallback was used |
| `items_requested` | integer | Count from original order |
| `items_added` | integer | Successfully added |
| `items_excluded` | integer | Excluded (unavailable) |
| `created_at` | timestamp | Attempt time |

---

## Acceptance Criteria

- [ ] **Given** a customer reorders from a past order and the original pharmacy is open and all items are in stock, **when** the reorder succeeds, **then** a new cart is created with the same items and quantities at the original pharmacy.
- [ ] **Given** the original pharmacy is closed, **when** the customer calls reorder with `confirm_pharmacy_change: false`, **then** the API returns HTTP 409 with `PHARMACY_CHANGE_REQUIRED` and the suggested fallback pharmacy details.
- [ ] **Given** the customer confirms with `confirm_pharmacy_change: true`, **when** the reorder succeeds with a fallback pharmacy, **then** the cart is created at the fallback pharmacy and the response includes a `pharmacy.note` explaining why the pharmacy changed.
- [ ] **Given** a reorder has Rx-only items, **when** the cart is created, **then** `prescription_attached: false` and `prescription_required: true` are in the response, and the original prescription is NOT pre-attached.
- [ ] **Given** all items from the original order are unavailable at any nearby pharmacy, **when** reorder is called, **then** the API returns HTTP 422 with `NO_ITEMS_AVAILABLE`.
- [ ] **Given** a past order had 3 items and 1 is unavailable at the fallback pharmacy, **when** reorder succeeds, **then** `items_added = 2`, `excluded_items` has 1 entry with the correct reason.
- [ ] **Given** `GET /api/v1/orders/history` is called, **when** the response is received, **then** only DELIVERED and CANCELLED orders appear (no active orders in history).
- [ ] **Given** a customer has 2 active orders simultaneously, **when** `GET /api/v1/orders/active` is called, **then** both orders appear in the response.

---

## Dependencies

| Dependency | Story / System | Notes |
|------------|---------------|-------|
| EPIC-010 STORY-001 - Cart management | Downstream | Reorder creates a new cart |
| EPIC-010 STORY-002 - Smart pharmacy selection | Upstream | Fallback pharmacy selection |
| EPIC-006 - Pharmacy inventory | Upstream | Live stock check for reorder items |

---

## Notes

- `items_preview` in order history is the first 3 item names (truncated) to fit a single-line card on mobile. Formatted as `{name} - {quantity}`.
- The reorder endpoint must handle the case where the customer already has an active cart: if the existing cart is non-empty, a confirmation prompt is expected client-side. The API optionally accepts `clear_existing_cart: true` to allow the backend to clear it automatically (v2 UX consideration; in v1, the client handles this).
