# STORY-006: Order Cancellation and Refund

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-006 |
| **Epic** | EPIC-010 - Order Management |
| **Priority** | P0 |
| **Complexity** | M |
| **Status** | Draft |

---

## Overview

This story defines all order cancellation paths and the refund routing logic for Namma MedMate. Customers can self-cancel only in the earliest order states; all later cancellations require admin intervention. The refund destination depends on the original payment method: online payments revert to the source account via Razorpay, COD returns go to the Namma Money wallet (since no pre-payment was made, a partial refund for handling/delivery fee may apply in certain edge cases), and wallet payments return to the wallet. Admin can issue partial refunds for disputed amounts. All refunds are logged in the finance module and linked to the originating order.

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| `customer` | Self-cancel (limited states) | Cancel own orders in PENDING_ACCEPTANCE or ACCEPTED only |
| `admin_super` | Full cancel + refund | Cancel at any state, issue any refund amount |
| `admin_operations` | Full cancel + refund | Same as admin_super for order cancellations |
| `admin_finance` | Refund only | Issue refunds; cannot cancel orders |

---

## Business Rules

1. **Customer cancellation window:** A customer can cancel their own order only when `status` is `PENDING_ACCEPTANCE` or `ACCEPTED`. Once the order moves to `PACKING` or later, only an admin can cancel.
2. **Admin cancellation scope:** Admin can cancel an order at any status except `DELIVERED` (terminal). A delivered order cannot be cancelled; it can only have a refund issued.
3. **Refund routing rules:** (a) Online payments (UPI/Card) ? refunded to original source account via Razorpay `refund` API (3-5 business days). (b) Wallet payments ? refunded to Namma Money wallet (instant). (c) COD ? refunded to Namma Money wallet (since cash cannot be reversed digitally). (d) Split payments (wallet + online method) ? wallet portion to wallet, online portion to source.
4. **Auto-refund on auto-cancellation:** When the system auto-cancels an order (pharmacy acceptance timeout, pharmacy rejection, no rider after 30 minutes), a full refund is automatically initiated per the routing rules above.
5. **Partial refund for post-PACKING admin cancellations:** If an admin cancels an order after `PACKING` has started, a partial refund may be issued (e.g., deducting packing/handling costs). The admin specifies `refund_amount` explicitly. The system does not auto-calculate partial refunds.
6. **COD handling fee refund:** For COD orders cancelled before delivery, no payment was collected, so no refund is due. However, if a COD order is cancelled after `READY_FOR_PICKUP` (rare), any out-of-pocket rider dispatch cost may be captured as an internal note. No customer-facing refund for pre-delivery COD.
7. **Refund record in finance:** Every refund (auto or manual) creates a `Refund` record linked to the order and is surfaced in the admin finance module for reconciliation.
8. **Cancellation notification:** On any cancellation (customer, admin, or auto), the customer receives a push notification and WhatsApp message with the reason and refund status.

---

## API Endpoints

### 1. Customer - Cancel Order

```POST /api/v1/orders/:order_id/cancel```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 5 req/min

**Request Body:**
```json
{
  "reason": "CHANGED_MIND"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `reason` | ENUM | Yes | `CHANGED_MIND`, `WRONG_ITEMS`, `PHARMACY_DELAY`, `OTHER` |

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "order_id": "ord_01J3KP7VDEF789",
    "order_number": "ORD-20260724-00123",
    "status": "CANCELLED",
    "cancelled_by": "customer",
    "cancelled_at": "2026-07-24T11:38:00Z",
    "refund": {
      "initiated": true,
      "amount": 221.25,
      "refund_to": "SOURCE",
      "estimated_days": 5,
      "message": "Refund of Rs 221.25 will be credited to your original payment method in 3-5 business days."
    }
  }
}
```

**Errors:**

| Code | HTTP | Description |
|------|------|-------------|
| `ORDER_NOT_FOUND` | 404 | Order not found for this customer |
| `ORDER_CANNOT_CANCEL` | 409 | Order status is PACKING or later; customer cannot cancel |
| `ORDER_ALREADY_CANCELLED` | 409 | Order is already cancelled |

---

### 2. Admin - Cancel Order

```POST /api/v1/admin/orders/:order_id/cancel```

**Authentication:** Bearer JWT - `admin_super` | `admin_operations`
**Rate Limit:** 10 req/min

**Request Body:**
```json
{
  "reason": "PHARMACY_REJECTED_AFTER_PACKING",
  "refund_amount": 221.25,
  "refund_to": "SOURCE"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `reason` | string | Yes | Free text cancellation reason (max 300 chars) |
| `refund_amount` | number | Yes | Amount to refund (? order total_payable) |
| `refund_to` | ENUM | Yes | `SOURCE` or `WALLET` |

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "order_id": "ord_01J3KP7VDEF789",
    "status": "CANCELLED",
    "cancelled_by": "admin_01J3KP7VEEE555",
    "cancelled_at": "2026-07-24T12:00:00Z",
    "refund": {
      "refund_id": "ref_01J3KP7VWWW222",
      "amount": 221.25,
      "refund_to": "SOURCE",
      "status": "INITIATED"
    }
  }
}
```

**Errors:**

| Code | HTTP | Description |
|------|------|-------------|
| `ORDER_NOT_FOUND` | 404 | Order ID not found |
| `ORDER_DELIVERED` | 409 | Cannot cancel a delivered order |
| `REFUND_EXCEEDS_ORDER_TOTAL` | 422 | `refund_amount > order.total_payable` |

---

### 3. Admin - Issue Refund

```POST /api/v1/admin/orders/:order_id/refund```

**Authentication:** Bearer JWT - `admin_super` | `admin_operations` | `admin_finance`
**Rate Limit:** 10 req/min

**Request Body:**
```json
{
  "amount": 50.00,
  "refund_to": "WALLET",
  "reason": "Partial refund for missing item (Glipizide 5mg not delivered)",
  "notes": "Customer confirmed missing item via WhatsApp. Approved by Ops lead."
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `amount` | number | Yes | Refund amount in Rs |
| `refund_to` | ENUM | Yes | `SOURCE` or `WALLET` |
| `reason` | string | Yes | Reason (max 300 chars) |
| `notes` | string | No | Internal notes (max 500 chars) |

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "refund_id": "ref_01J3KP7VXXX333",
    "order_id": "ord_01J3KP7VDEF789",
    "amount": 50.00,
    "refund_to": "WALLET",
    "status": "PROCESSED",
    "processed_at": "2026-07-24T14:00:00Z",
    "issued_by": "admin_01J3KP7VEEE555",
    "razorpay_refund_id": null
  }
}
```

**Errors:**

| Code | HTTP | Description |
|------|------|-------------|
| `ORDER_NOT_FOUND` | 404 | Order ID not found |
| `REFUND_EXCEEDS_REMAINING_REFUNDABLE` | 422 | Total refunds would exceed `total_payable` |

---

### 4. Admin - Check Refund Eligibility

```GET /api/v1/admin/orders/:order_id/refund-eligibility```

**Authentication:** Bearer JWT - `admin_super` | `admin_operations` | `admin_finance`
**Rate Limit:** 30 req/min

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "order_id": "ord_01J3KP7VDEF789",
    "order_total": 221.25,
    "already_refunded": 0.00,
    "max_refundable": 221.25,
    "eligible": true,
    "payment_method": "UPI",
    "original_payment_status": "PAID",
    "recommendation": {
      "refund_to": "SOURCE",
      "message": "Order was paid via UPI. Refund to source recommended (3-5 business days via Razorpay)."
    },
    "cancellation_eligible": true,
    "cancellation_reason": "Order is in ACCEPTED status - eligible for customer or admin cancellation"
  }
}
```

---

## Data Models

### Refund

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID v4 | PK, NOT NULL | Refund identifier |
| `order_id` | UUID | FK ? orders.id, NOT NULL | Originating order |
| `amount` | decimal(10,2) | NOT NULL, > 0 | Refund amount |
| `refund_to` | ENUM | NOT NULL | `SOURCE`, `WALLET` |
| `reason` | string | NOT NULL | Refund reason |
| `notes` | string | nullable | Internal notes |
| `status` | ENUM | NOT NULL | `INITIATED`, `PROCESSED`, `FAILED` |
| `issued_by` | UUID | FK ? users.id, nullable | Admin who issued (null for auto-refunds) |
| `issued_by_type` | ENUM | NOT NULL | `ADMIN`, `SYSTEM`, `PHARMACY` |
| `razorpay_refund_id` | string | nullable | Razorpay refund ID for SOURCE refunds |
| `wallet_transaction_id` | UUID | nullable | Wallet tx ID for WALLET refunds |
| `processed_at` | timestamp | nullable | When refund was confirmed processed |
| `failed_reason` | string | nullable | Set if status = FAILED |
| `created_at` | timestamp | NOT NULL | Refund creation time |

### OrderCancellation

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID v4 | PK, NOT NULL | Cancellation record identifier |
| `order_id` | UUID | FK ? orders.id, UNIQUE | One cancellation per order |
| `cancelled_by_type` | ENUM | NOT NULL | `CUSTOMER`, `PHARMACY`, `ADMIN`, `SYSTEM` |
| `cancelled_by_id` | UUID | nullable | User ID of canceller |
| `reason` | string | NOT NULL | Cancellation reason |
| `cancelled_at` | timestamp | NOT NULL | Cancellation timestamp |

---

## Acceptance Criteria

- [ ] **Given** a customer attempts to cancel an order in `PACKING` status, **when** the cancel request is made, **then** the API returns HTTP 409 with `ORDER_CANNOT_CANCEL`.
- [ ] **Given** a customer cancels an order paid via UPI, **when** the cancellation succeeds, **then** a `Refund` record is created with `refund_to: SOURCE` and the Razorpay refund API is called within 60 seconds.
- [ ] **Given** an admin cancels a COD order before delivery, **when** cancellation is saved, **then** no Razorpay refund is triggered and the response `refund.initiated = false`.
- [ ] **Given** an admin issues a partial refund of Rs 50 to wallet on a delivered order, **when** the refund is processed, **then** the customer's Namma Money wallet balance increases by Rs 50 and a `Refund` record is created.
- [ ] **Given** a refund of Rs 250 is attempted on an order with `total_payable = Rs 221.25`, **when** the refund request is submitted, **then** the API returns HTTP 422 with `REFUND_EXCEEDS_REMAINING_REFUNDABLE`.
- [ ] **Given** the system auto-cancels an order due to pharmacy acceptance timeout, **when** the auto-cancel triggers, **then** a `Refund` and `OrderCancellation` record are created with `cancelled_by_type: SYSTEM`.
- [ ] **Given** an admin attempts to cancel a `DELIVERED` order, **when** the cancel request is submitted, **then** the API returns HTTP 409 with `ORDER_DELIVERED`.
- [ ] **Given** an order is cancelled for any reason, **when** the cancellation succeeds, **then** the customer receives a push notification and WhatsApp message within 30 seconds with reason and refund status.

---

## Dependencies

| Dependency | Story / System | Notes |
|------------|---------------|-------|
| EPIC-010 STORY-005 - Order lifecycle | Upstream | Cancellation is a status transition |
| Razorpay refund API | External | `POST /v1/payments/{id}/refund` |
| EPIC-012 - Namma Money wallet | Downstream | Wallet credit for WALLET refunds |
| Notification service (Push + WhatsApp) | Platform | Cancellation and refund notifications |
| Finance module | Downstream | All refunds logged for reconciliation |

---

## Notes

- Razorpay refunds are initiated server-side. The Razorpay webhook `refund.processed` updates the `Refund` record to `status: PROCESSED` and sets `razorpay_refund_id`.
- For split payment orders (wallet + online), the refund split must mirror the original split: wallet portion back to wallet, online portion back to source. The `admin/orders/:order_id/refund` endpoint accepts `refund_to` for each component (out of scope for v1 - treated as single refund destination chosen by admin with max = online_portion).
- The maximum total refundable amount = `order.total_payable - sum(existing refunds)`. This prevents over-refunding.
