# STORY-004: Order Placement and Payment

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-004 |
| **Epic** | EPIC-010 - Order Management |
| **Priority** | P0 |
| **Complexity** | L |
| **Status** | Draft |

---

## Overview

This story defines the order creation flow - the critical transaction moment where a customer converts their assembled cart into a confirmed order. The placement endpoint performs a series of pre-order validations (pharmacy open, all items in stock, prescription attached if needed, address in zone) before initiating payment via Razorpay. COD orders confirm immediately; online payments (UPI/Card) confirm via a payment webhook or client-side confirmation endpoint. The Namma Money wallet is always applied first (up to the remaining order total), and the balance is charged via the customer's chosen payment method. On confirmation, the pharmacy receives a WhatsApp notification and in-app push.

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| `customer` | Full access | Place orders, confirm payments |
| `rider` | COD collection | Mark COD collected at delivery |
| `admin_super` | Read-only (orders endpoint) | View order detail |
| `pharmacy_owner` | Receive notification | Handled via STORY-005 accept/reject |

---

## Business Rules

1. **Pre-order validation checklist:** Before creating an order, all of the following must pass: (a) cart is non-empty; (b) `pharmacy.is_active = true` and `pharmacy.is_online = true`; (c) all items have `quantity_available ? requested_quantity` (live check, not just cart snapshot); (d) if any item is `is_rx_required = true`, a prescription with status `VERIFIED` or `UPLOADED` must be attached; (e) `delivery_address_id` is set and within the pharmacy's serviceable zone. Any failure aborts the order with a specific error code.
2. **Wallet-first deduction:** Namma Money wallet balance is deducted first. The wallet can cover the entire order total, in which case no additional payment method is needed. If `wallet_balance < order_total`, the remainder is charged via `payment_method`.
3. **COD confirmation:** COD orders are confirmed immediately on `POST /api/v1/orders` - no payment token required. `payment.status = PENDING_COLLECTION`. The order proceeds to `PENDING_ACCEPTANCE`.
4. **Online payment flow:** UPI/Card orders are created in `PAYMENT_PENDING` status. The client completes Razorpay payment and calls `POST /api/v1/orders/:order_id/payment/confirm` with the Razorpay `payment_id` and `payment_signature`. The server verifies the signature against `razorpay_order_id + "|" + payment_id` using the Razorpay secret key. Successful verification ? order confirmed.
5. **Order number format:** `ORD-{YYYYMMDD}-{5-digit-seq}` where the sequence is global, reset daily at midnight IST. Example: `ORD-20260724-00123`.
6. **Pharmacy notification on confirmation:** On order confirmation (COD or online payment success), a WhatsApp message and push notification are sent to the pharmacy owner and staff with order details, item list, and a 10-minute acceptance deadline.
7. **Idempotency for payment confirm:** `POST /api/v1/orders/:order_id/payment/confirm` is idempotent - duplicate calls with the same `payment_id` return the already-confirmed order rather than triggering an error or double-confirmation.
8. **Cart invalidation post-order:** On successful order placement, the cart's `status` transitions to `CHECKED_OUT` and can no longer be modified. A new fresh cart is available for the customer immediately.

---

## API Endpoints

### 1. Place Order

```POST /api/v1/orders```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 5 req/min per customer

**Request Body:**
```json
{
  "cart_id": "cart_01J3KP7VGHJ000",
  "payment_method": "UPI",
  "payment_token": null,
  "delivery_instructions": "Leave with security guard if not home"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `cart_id` | UUID | Yes | Cart to convert to order |
| `payment_method` | ENUM | Yes | `UPI`, `CARD`, `COD`, `WALLET` |
| `payment_token` | string | If UPI/CARD | Razorpay payment token (can be null for COD or WALLET) |
| `delivery_instructions` | string | No | Instructions for rider (max 200 chars) |

**Response `201 Created` (COD or WALLET full payment):**
```json
{
  "success": true,
  "data": {
    "order_id": "ord_01J3KP7VDEF789",
    "order_number": "ORD-20260724-00123",
    "status": "PENDING_ACCEPTANCE",
    "pharmacy": {
      "id": "ph_01J3KP7VFFF666",
      "name": "Sai Medicals",
      "area": "Koramangala"
    },
    "items": [
      { "name": "Metformin 500mg (Glycomet)", "quantity": 3, "price": 85.00, "line_total": 255.00 }
    ],
    "bill": {
      "item_total": 255.00,
      "coupon_discount": 63.75,
      "subtotal_after_discount": 191.25,
      "delivery_fee": 25.00,
      "handling_fee": 5.00,
      "wallet_applied": 0.00,
      "total_payable": 221.25
    },
    "payment": {
      "method": "COD",
      "status": "PENDING_COLLECTION",
      "transaction_id": null,
      "razorpay_order_id": null
    },
    "delivery_address": {
      "full_address": "42, 5th Cross, Koramangala 4th Block, Bengaluru 560034"
    },
    "estimated_delivery_at": "2026-07-24T11:52:00Z",
    "created_at": "2026-07-24T11:30:00Z"
  }
}
```

**Response `201 Created` (UPI/Card - awaiting payment):**
```json
{
  "success": true,
  "data": {
    "order_id": "ord_01J3KP7VDEF789",
    "order_number": "ORD-20260724-00123",
    "status": "PAYMENT_PENDING",
    "payment": {
      "method": "UPI",
      "status": "AWAITING_PAYMENT",
      "razorpay_order_id": "order_Razorpay12345",
      "amount_paise": 22125
    },
    "created_at": "2026-07-24T11:30:00Z"
  }
}
```

**Errors:**

| Code | HTTP | Description |
|------|------|-------------|
| `CART_EMPTY` | 422 | Cart has no items |
| `PHARMACY_OFFLINE` | 422 | Pharmacy is not accepting online orders |
| `ITEMS_OUT_OF_STOCK` | 422 | One or more items unavailable; returns `out_of_stock_items` array |
| `PRESCRIPTION_REQUIRED` | 422 | Cart has Rx-only items without prescription attached |
| `ADDRESS_NOT_SET` | 422 | No delivery address on cart |
| `ADDRESS_OUT_OF_ZONE` | 422 | Address outside pharmacy serviceable zone |
| `PAYMENT_INITIATION_FAILED` | 502 | Razorpay order creation failed |

---

### 2. Get Order Detail

```GET /api/v1/orders/:order_id```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 60 req/min

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "id": "ord_01J3KP7VDEF789",
    "order_number": "ORD-20260724-00123",
    "status": "PACKING",
    "pharmacy": {
      "id": "ph_01J3KP7VFFF666",
      "name": "Sai Medicals",
      "area": "Koramangala",
      "phone": "+91-8022334455"
    },
    "rider": null,
    "items": [
      { "name": "Metformin 500mg (Glycomet)", "quantity": 3, "unit_price": 85.00, "line_total": 255.00 }
    ],
    "bill": {
      "item_total": 255.00,
      "coupon_discount": 63.75,
      "subtotal_after_discount": 191.25,
      "delivery_fee": 25.00,
      "handling_fee": 5.00,
      "wallet_applied": 0.00,
      "total_payable": 221.25
    },
    "prescription_id": "rx_01J3KP7VLLL222",
    "delivery_address": {
      "full_address": "42, 5th Cross, Koramangala 4th Block, Bengaluru 560034"
    },
    "delivery_instructions": "Leave with security guard if not home",
    "payment": {
      "method": "UPI",
      "status": "PAID",
      "transaction_id": "pay_Razorpay98765",
      "razorpay_order_id": "order_Razorpay12345"
    },
    "estimated_delivery_at": "2026-07-24T11:52:00Z",
    "confirmed_at": "2026-07-24T11:31:00Z",
    "created_at": "2026-07-24T11:30:00Z"
  }
}
```

---

### 3. Confirm Payment

```POST /api/v1/orders/:order_id/payment/confirm```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 10 req/min

**Request Body:**
```json
{
  "payment_id": "pay_Razorpay98765",
  "payment_signature": "sha256_hmac_signature_string"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `payment_id` | string | Yes | Razorpay payment_id |
| `payment_signature` | string | Yes | HMAC-SHA256 of `razorpay_order_id|payment_id` |

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "order_id": "ord_01J3KP7VDEF789",
    "order_number": "ORD-20260724-00123",
    "status": "PENDING_ACCEPTANCE",
    "payment": {
      "method": "UPI",
      "status": "PAID",
      "transaction_id": "pay_Razorpay98765"
    },
    "pharmacy_notified": true,
    "confirmed_at": "2026-07-24T11:31:00Z"
  }
}
```

**Errors:**

| Code | HTTP | Description |
|------|------|-------------|
| `PAYMENT_SIGNATURE_INVALID` | 422 | HMAC verification failed |
| `ORDER_NOT_IN_PAYMENT_PENDING` | 409 | Order is not awaiting payment |

---

### 4. Confirm COD Collection (Rider)

```POST /api/v1/orders/:order_id/payment/cod-collect```

**Authentication:** Bearer JWT - `rider`
**Rate Limit:** 10 req/min

**Request Body:**
```json
{
  "amount_collected": 221.25
}
```

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "order_id": "ord_01J3KP7VDEF789",
    "payment_status": "COLLECTED",
    "amount_collected": 221.25,
    "collected_at": "2026-07-24T11:55:00Z"
  }
}
```

---

## Data Models

### Order

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID v4 | PK, NOT NULL | Order identifier |
| `order_number` | string | UNIQUE, NOT NULL | Human-readable `ORD-YYYYMMDD-XXXXX` |
| `customer_id` | UUID | FK ? customers.id, NOT NULL | Ordering customer |
| `pharmacy_id` | UUID | FK ? pharmacies.id, NOT NULL | Fulfilling pharmacy |
| `cart_id` | UUID | FK ? carts.id, NOT NULL | Source cart |
| `items` | JSONB | NOT NULL | Snapshot of order items at placement |
| `item_total` | decimal(10,2) | NOT NULL | Sum of item line totals |
| `coupon_code` | string | nullable | Applied coupon |
| `coupon_discount` | decimal(10,2) | default 0 | Coupon discount amount |
| `delivery_fee` | decimal(10,2) | NOT NULL | Rs 25 or Rs 0 |
| `handling_fee` | decimal(10,2) | NOT NULL, default 5 | Rs 5 |
| `wallet_applied` | decimal(10,2) | default 0 | Wallet deducted |
| `total_payable` | decimal(10,2) | NOT NULL | Final payable amount |
| `payment_method` | ENUM | NOT NULL | `UPI`, `CARD`, `COD`, `WALLET` |
| `payment_status` | ENUM | NOT NULL | `PENDING_COLLECTION`, `AWAITING_PAYMENT`, `PAID`, `REFUNDED`, `PARTIALLY_REFUNDED` |
| `razorpay_order_id` | string | nullable | Razorpay order ID for online payments |
| `razorpay_payment_id` | string | nullable | Razorpay payment ID on capture |
| `prescription_id` | UUID | FK ? prescriptions.id, nullable | Attached prescription |
| `delivery_address_id` | UUID | FK ? addresses.id, NOT NULL | Delivery address |
| `delivery_instructions` | string | nullable | Customer instructions for rider |
| `status` | ENUM | NOT NULL | See STORY-005 state machine |
| `rider_id` | UUID | FK ? riders.id, nullable | Assigned rider |
| `delivery_otp` | string(4) | nullable | OTP generated at READY_FOR_PICKUP |
| `confirmed_at` | timestamp | nullable | Order confirmation time |
| `estimated_delivery_at` | timestamp | nullable | ETA computed on confirmation |
| `created_at` | timestamp | NOT NULL | Order creation |
| `updated_at` | timestamp | NOT NULL | Last update |

---

## Acceptance Criteria

- [ ] **Given** a cart with an Rx-only item and no prescription attached, **when** `POST /api/v1/orders` is called, **then** the API returns HTTP 422 with `PRESCRIPTION_REQUIRED` and the order is not created.
- [ ] **Given** a pharmacy's stock for an item drops to 0 after the customer added it to cart, **when** `POST /api/v1/orders` is called, **then** the live stock check fails and `ITEMS_OUT_OF_STOCK` is returned with the specific item name.
- [ ] **Given** a COD order is placed, **when** the order is created, **then** status is `PENDING_ACCEPTANCE` and `payment.status = PENDING_COLLECTION` without requiring any payment confirmation step.
- [ ] **Given** a UPI order is placed and `POST /api/v1/orders/:order_id/payment/confirm` is called with an invalid `payment_signature`, **then** the API returns HTTP 422 with `PAYMENT_SIGNATURE_INVALID`.
- [ ] **Given** `POST /api/v1/orders/:order_id/payment/confirm` is called twice with the same `payment_id`, **when** the second call is received, **then** the already-confirmed order is returned without error (idempotent).
- [ ] **Given** an order is confirmed, **when** the confirmation succeeds, **then** the pharmacy receives a WhatsApp notification within 30 seconds with order number, items, and delivery address.
- [ ] **Given** the customer's wallet has Rs 50 and the order total is Rs 221.25, **when** the order is placed, **then** `wallet_applied = 50.00`, `total_payable` via chosen method = Rs 171.25.
- [ ] **Given** an order is successfully placed, **when** the cart is accessed, **then** cart `status = CHECKED_OUT` and items cannot be modified.

---

## Dependencies

| Dependency | Story / System | Notes |
|------------|---------------|-------|
| EPIC-010 STORY-001 - Cart management | Upstream | Cart state validated at placement |
| EPIC-010 STORY-002 - Pharmacy selection | Upstream | Pharmacy open/online check |
| EPIC-008 STORY-001 - Prescription | Upstream | Prescription status validation |
| Razorpay payment gateway | External | Order creation and payment capture |
| EPIC-012 - Namma Money wallet | Upstream | Wallet deduction at order placement |
| Notification service (WhatsApp + Push) | Platform | Pharmacy notification on confirmation |
| EPIC-010 STORY-005 - Order lifecycle | Downstream | Order enters state machine after creation |

---

## Notes

- Razorpay signature verification: `HMAC_SHA256(razorpay_order_id + "|" + razorpay_payment_id, razorpay_key_secret)`. This must match the `payment_signature` from the client.
- `estimated_delivery_at = confirmed_at + pharmacy.avg_prep_time_minutes + delivery_eta_minutes`.
- A Razorpay webhook (`payment.captured`) should also trigger order confirmation as a fallback in case the client's `payment/confirm` call is dropped. The webhook and client-side confirm are idempotent on the same `payment_id`.
