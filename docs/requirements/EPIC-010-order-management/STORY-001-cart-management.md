# STORY-001: Customer Cart Lifecycle

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-001 |
| **Epic** | EPIC-010 - Order Management |
| **Priority** | P0 |
| **Complexity** | L |
| **Status** | Draft |

---

## Overview

This story defines the full lifecycle of a customer's shopping cart on Namma MedMate - from the moment the first medicine is added through checkout. A cart is always locked to a single pharmacy, which is auto-selected by the smart pharmacy engine when the first item is added to an empty cart. The cart supports coupon application, prescription attachment, delivery address selection, and quantity management. The bill is computed dynamically on every state change applying all discount and fee rules. The cart persists until checked out, manually cleared, or auto-abandoned after 24 hours of inactivity.

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| `customer` | Full access | Create, read, update, clear their own cart |
| `admin_support` | Read-only | View for dispute resolution |
| `pharmacy_owner` | None | Cannot view customer carts (only orders) |

---

## Business Rules

1. **One pharmacy per cart:** The cart is locked to a single pharmacy from the moment the first item is added. Adding an item from a different pharmacy prompts a switch confirmation (client-side); the API `POST /api/v1/cart/items` rejects the item with `PHARMACY_MISMATCH` if the pharmacy doesn't match, unless `switch_pharmacy: true` is included.
2. **Smart pharmacy auto-selection:** On the first `POST /api/v1/cart/items` call with an empty cart, the backend runs the smart-select algorithm (STORY-002) to select the optimal pharmacy for the added medicine, then creates the cart locked to that pharmacy.
3. **Fee calculation:** Handling fee = Rs 5 (always applied to any non-empty cart). Delivery fee = Rs 25 if item_total < Rs 199, else Rs 0. `FREEDEL` coupon overrides delivery fee to Rs 0 regardless of item_total.
4. **Coupon rules:** Only one coupon can be applied at a time. `NAMMA25`: 25% off `item_total`. `FLAT50`: Rs 50 off `item_total` - only valid if `item_total ? Rs 399`; rejected with `COUPON_MIN_NOT_MET` otherwise. `FREEDEL`: delivery fee = Rs 0. Invalid or expired coupon code returns `INVALID_COUPON`. Coupon discount is capped at `item_total` (cannot result in negative).
5. **Prescription requirement:** If the cart contains at least one Rx-only item, a prescription must be attached before checkout. Checkout without a prescription for an Rx item returns `PRESCRIPTION_REQUIRED`.
6. **Cart clearing on item removal:** If all items are removed (either via `DELETE /api/v1/cart/items/:item_id` with quantity=0 or `DELETE /api/v1/cart`), the cart is cleared: `pharmacy_id = null`, `coupon = null`, `prescription = null`, `status = ACTIVE`.
7. **Namma Money wallet:** Wallet balance is not deducted in the cart - it is applied at checkout (order placement). The cart `bill` shows `wallet_applied` as an estimate based on the current wallet balance, but actual deduction happens in `POST /api/v1/orders`.
8. **Abandoned cart:** A cart in `ACTIVE` status with no updates for 24 hours transitions to `ABANDONED` status via a scheduled job. Abandoned carts cannot be updated; the customer must start a new cart.

---

## API Endpoints

### 1. Get Current Cart

```GET /api/v1/cart```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 60 req/min

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "cart_id": "cart_01J3KP7VGHJ000",
    "status": "ACTIVE",
    "pharmacy": {
      "id": "ph_01J3KP7VFFF666",
      "name": "Sai Medicals",
      "area": "Koramangala, Bengaluru",
      "eta_minutes": 22,
      "is_open": true,
      "rating": 4.6
    },
    "items": [
      {
        "item_id": "ci_01J3KP7VNNN444",
        "product": {
          "id": "prod_01J3KP7VOOO555",
          "name": "Metformin 500mg (Glycomet)",
          "brand": "USV Ltd",
          "pack_size": "10 tablets",
          "is_rx_required": true,
          "image_url": "https://cdn.nammamedmate.com/products/metformin-500.jpg"
        },
        "quantity": 3,
        "unit_price": 85.00,
        "line_total": 255.00
      }
    ],
    "coupon_applied": "NAMMA25",
    "coupon_discount": 63.75,
    "prescription_id": "rx_01J3KP7VLLL222",
    "prescription_status": "VERIFIED",
    "delivery_address": {
      "id": "addr_01J3KP7VPPP666",
      "label": "Home",
      "full_address": "42, 5th Cross, Koramangala 4th Block, Bengaluru 560034",
      "lat": 12.9345,
      "lng": 77.6125
    },
    "bill": {
      "item_total": 255.00,
      "coupon_discount": 63.75,
      "subtotal_after_discount": 191.25,
      "delivery_fee": 25.00,
      "handling_fee": 5.00,
      "wallet_applied": 0.00,
      "total_payable": 221.25
    },
    "created_at": "2026-07-24T09:00:00Z",
    "updated_at": "2026-07-24T10:15:00Z"
  }
}
```

---

### 2. Add Item to Cart

```POST /api/v1/cart/items```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 30 req/min

**Request Body:**
```json
{
  "medicine_id": "prod_01J3KP7VOOO555",
  "quantity": 3
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `medicine_id` | UUID | Yes | Product/medicine ID |
| `quantity` | integer | Yes | Quantity to add (> 0) |
| `switch_pharmacy` | boolean | No | If true + pharmacy mismatch, clear cart and add to new pharmacy |

**Response `200 OK`:** Returns updated cart (same schema as GET /api/v1/cart).

**Errors:**

| Code | HTTP | Description |
|------|------|-------------|
| `MEDICINE_NOT_FOUND` | 404 | Product ID not found |
| `OUT_OF_STOCK` | 422 | Medicine out of stock at selected pharmacy |
| `PHARMACY_MISMATCH` | 409 | Item is from a different pharmacy; requires switch_pharmacy: true |
| `NO_PHARMACY_AVAILABLE` | 422 | No open pharmacy stocks this medicine within 5km |

---

### 3. Update Cart Item Quantity

```PATCH /api/v1/cart/items/:item_id```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 30 req/min

**Request Body:**
```json
{
  "quantity": 5
}
```

*Quantity = 0 removes the item. If this was the last item, the cart is cleared.*

**Response `200 OK`:** Returns updated cart.

---

### 4. Clear Cart

```DELETE /api/v1/cart```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 10 req/min

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "message": "Cart cleared",
    "cart_id": "cart_01J3KP7VGHJ000",
    "status": "ACTIVE"
  }
}
```

---

### 5. Apply Coupon

```POST /api/v1/cart/coupon```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 10 req/min

**Request Body:**
```json
{ "coupon_code": "FLAT50" }
```

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "coupon_code": "FLAT50",
    "discount_type": "FLAT",
    "discount_amount": 50.00,
    "message": "Rs 50 discount applied"
  }
}
```

**Errors:**

| Code | HTTP | Description |
|------|------|-------------|
| `INVALID_COUPON` | 422 | Code not found or expired |
| `COUPON_MIN_NOT_MET` | 422 | FLAT50 requires min cart of Rs 399 |
| `COUPON_ALREADY_APPLIED` | 409 | A different coupon is already applied |

---

### 6. Remove Coupon

```DELETE /api/v1/cart/coupon```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 10 req/min

**Response `200 OK`:** Returns updated cart with no coupon.

---

### 7. Attach Prescription to Cart

```POST /api/v1/cart/prescription```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 10 req/min

**Request Body:**
```json
{ "prescription_id": "rx_01J3KP7VLLL222" }
```

**Response `200 OK`:** Returns updated cart with `prescription_id` set.

---

### 8. Remove Prescription from Cart

```DELETE /api/v1/cart/prescription```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 10 req/min

**Response `200 OK`:** Returns updated cart with `prescription_id: null`.

---

### 9. Set Delivery Address

```POST /api/v1/cart/address```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 10 req/min

**Request Body:**
```json
{ "address_id": "addr_01J3KP7VPPP666" }
```

**Response `200 OK`:** Returns updated cart with delivery address set.

**Errors:**

| Code | HTTP | Description |
|------|------|-------------|
| `ADDRESS_NOT_FOUND` | 404 | Address not in customer's address book |
| `ADDRESS_OUT_OF_ZONE` | 422 | Delivery address outside pharmacy's serviceable zone |

---

### 10. Switch Pharmacy

```POST /api/v1/cart/switch-pharmacy```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 5 req/min

**Request Body:**
```json
{
  "pharmacy_id": "ph_01J3KP7VQQQ777",
  "confirm": true
}
```

*`confirm: true` is required; prevents accidental pharmacy switches. Clears all cart items.*

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "cart_id": "cart_01J3KP7VGHJ000",
    "pharmacy": { "id": "ph_01J3KP7VQQQ777", "name": "Apollo Pharmacy", "area": "BTM Layout" },
    "items": [],
    "message": "Cart cleared and switched to Apollo Pharmacy"
  }
}
```

---

## Data Models

### Cart

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID v4 | PK, NOT NULL | Cart identifier |
| `customer_id` | UUID | FK ? customers.id, UNIQUE (one active cart per customer) | Cart owner |
| `pharmacy_id` | UUID | FK ? pharmacies.id, nullable | Locked pharmacy |
| `items` | JSONB | NOT NULL, default `[]` | Array of cart item objects |
| `coupon_code` | string | nullable, max 20 | Applied coupon code |
| `coupon_discount` | decimal(10,2) | default 0 | Computed coupon discount amount |
| `prescription_id` | UUID | FK ? prescriptions.id, nullable | Attached prescription |
| `delivery_address_id` | UUID | FK ? addresses.id, nullable | Delivery address |
| `status` | ENUM | NOT NULL, default `ACTIVE` | `ACTIVE`, `CHECKED_OUT`, `ABANDONED` |
| `created_at` | timestamp | NOT NULL | Cart creation |
| `updated_at` | timestamp | NOT NULL | Last modification |

### CartItem (JSONB element schema)

| Field | Type | Description |
|-------|------|-------------|
| `item_id` | UUID | Cart item identifier (generated on add) |
| `product_id` | UUID | FK ? products.id |
| `quantity` | integer | Quantity in cart |
| `unit_price` | decimal | Price at time of add |
| `line_total` | decimal | unit_price - quantity |
| `is_rx_required` | boolean | Whether this item requires a prescription |

---

## Acceptance Criteria

- [ ] **Given** a customer adds the first item to an empty cart, **when** the item is added, **then** the smart-select algorithm runs and the cart is locked to the best-scoring pharmacy.
- [ ] **Given** a customer tries to add an item from a different pharmacy without `switch_pharmacy: true`, **when** the add request is made, **then** the API returns HTTP 409 with `PHARMACY_MISMATCH`.
- [ ] **Given** a customer applies coupon `FLAT50` to a cart with `item_total = Rs 350`, **when** the coupon is applied, **then** the API returns HTTP 422 with `COUPON_MIN_NOT_MET`.
- [ ] **Given** a coupon `NAMMA25` is applied to a cart with `item_total = Rs 255`, **when** the cart is fetched, **then** `coupon_discount = 63.75` and `delivery_fee = 25` (item_total before discount < Rs 199 threshold - delivery fee uses pre-discount total).
- [ ] **Given** all items are removed from a cart, **when** the last item is deleted, **then** `pharmacy_id` is set to null, `coupon_code` is cleared, and `prescription_id` is cleared.
- [ ] **Given** a cart has an Rx-only item and no prescription attached, **when** the customer proceeds to `POST /api/v1/orders`, **then** checkout is blocked with `PRESCRIPTION_REQUIRED`.
- [ ] **Given** a cart has been inactive for 24 hours, **when** the abandonment job runs, **then** the cart status transitions to `ABANDONED` and the customer cannot update it.
- [ ] **Given** `FREEDEL` coupon is applied to a cart with `item_total = Rs 150` (below Rs 199), **when** the bill is computed, **then** `delivery_fee = 0` (FREEDEL overrides the threshold rule).

---

## Dependencies

| Dependency | Story / System | Notes |
|------------|---------------|-------|
| EPIC-010 STORY-002 - Smart pharmacy selection | Upstream | Called on first item add |
| EPIC-008 STORY-001 - Prescription storage | Bidirectional | Prescription attached to cart |
| EPIC-010 STORY-004 - Order placement | Downstream | Cart is consumed by order creation |
| EPIC-006 - Pharmacy inventory | Upstream | Stock availability checked on item add |
| EPIC-012 - Namma Money wallet | Upstream | Wallet balance shown in bill estimate |
| Auth / RBAC | EPIC-001 | Customer JWT |

---

## Notes

- **Delivery fee threshold uses pre-coupon `item_total`**, not the discounted total. (E.g., `item_total = Rs 210` ? delivery free, even with `NAMMA25` applied reducing subtotal to Rs 157.50.)
- One active cart per customer at a time (enforced by unique constraint on `customer_id` where `status = ACTIVE`).
- Cart `items` JSONB snapshots the `unit_price` at the time of add. If pharmacy changes the price, the cart price does not auto-update; the checkout validation re-checks prices and updates if there is a discrepancy (with a notification to the customer).
