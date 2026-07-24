# STORY-001: Counter Sale POS - Real-Time Cart and Checkout

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-001 |
| **Epic** | EPIC-007 - Pharmacy POS & Billing |
| **Priority** | P0 |
| **Complexity** | XL |
| **Status** | Draft |

---

## Overview

This story defines the complete POS counter-sale API - from cart creation to checkout and invoice generation. A pharmacist opens a new cart session, adds products via barcode scan or text search, optionally attaches a customer, applies discounts, and checks out with a chosen payment method. The system enforces FEFO batch selection, validates Rx-medicine rules, deducts batch stock, and returns a finalized invoice. The POS is a core Free-plan feature available to all pharmacies.

---

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| `pharmacy_owner` | Full access | Create, manage, and checkout carts |
| `pharmacy_staff` | Full access | Create, manage, and checkout carts |
| `customer` | No access | Not applicable |
| `admin_super` | No access | Audit via sales ledger only |

---

## Business Rules

1. **FEFO batch auto-selection.** When a product is added to the cart without specifying `batch_id`, the system automatically selects the active batch with the earliest `expiry_date`. The pharmacist may override to a different batch manually.
2. **Rx medicine requires prescribing doctor.** If any item in the cart has `is_rx_only = true` (Schedule H, H1, or X), the checkout endpoint requires `prescribing_doctor` to be provided. If omitted, checkout is rejected with `RX_PRESCRIBER_REQUIRED`.
3. **Cart session expiry.** A cart auto-expires after 2 hours of inactivity (no add/edit/checkout operations). Expired carts have `status = ABANDONED` and cannot be checked out.
4. **Discount rules.** A single discount can be applied per cart. The maximum discount allowed is the lower of 30% of `subtotal` or Rs 500. Attempting to apply a higher discount returns `DISCOUNT_EXCEEDS_LIMIT`.
5. **CREDIT payment creates a Khata entry.** When `payment_method = CREDIT`, the sale amount is posted to the customer's Khata ledger. A `customer_id` (not walk-in) is required for CREDIT payments; otherwise `CREDIT_REQUIRES_NAMED_CUSTOMER` is returned.
6. **Loose selling unit price.** When `is_loose = true`, the `unit_price` per item is computed as `mrp_per_unit / pack_size`. The `pack_size` and `mrp_per_unit` are sourced from the assigned batch's product record.
7. **GST calculation per line.** Each cart item computes `gst_amount = line_total_pre_gst - gst_pct / (100 + gst_pct)` (GST inclusive of MRP). The invoice shows GST per slab in a breakdown table.
8. **Stock deduction on checkout.** The checkout operation is a database transaction: it deducts `quantity` from the selected `ProductBatch.quantity_current` and creates the invoice atomically. If a batch has insufficient stock at checkout time (race condition), `INSUFFICIENT_STOCK` is returned and the cart remains active.
9. **COD payment for online orders.** When `payment_method = COD`, the sale records the pre-linked online order as fulfilled. COD sales set invoice `payment_status = PENDING` until the rider marks delivery complete.
10. **Barcode scan auto-add.** `POST /cart/:id/search` with `mode = BARCODE` matches by product barcode field; on exact match, returns a single product with `auto_add = true` flag for the frontend to immediately add it to cart.

---

## API Endpoints

### 1. Create New Cart Session

```
POST /api/v1/pharmacy/pos/cart
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 60 req/min

**Request Body (application/json):**

```json
{
  "created_by_staff_id": "UUID - optional; defaults to authenticated user"
}
```

**Success Response - 201 Created:**

```json
{
  "success": true,
  "data": {
    "cart_id": "uuid",
    "status": "ACTIVE",
    "items": [],
    "customer": null,
    "discount_type": null,
    "discount_value": 0,
    "subtotal": 0,
    "gst_total": 0,
    "grand_total": 0,
    "expires_at": "2026-07-24T14:00:00Z",
    "created_at": "2026-07-24T12:00:00Z"
  }
}
```

---

### 2. Get Cart State

```
GET /api/v1/pharmacy/pos/cart/:cart_id
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 120 req/min

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "cart_id": "uuid",
    "status": "ACTIVE",
    "customer": {
      "customer_id": "uuid",
      "name": "Priya Sharma",
      "phone": "+919876000001"
    },
    "prescribing_doctor": null,
    "items": [
      {
        "item_id": "uuid",
        "product_id": "uuid",
        "product_name": "Paracetamol 500mg Tab",
        "batch_id": "uuid",
        "batch_number": "BN25100",
        "expiry_date": "2027-06-30",
        "quantity": 2,
        "is_loose": false,
        "unit_price": 22.50,
        "gst_pct": 12,
        "line_subtotal": 45.00,
        "gst_amount": 4.82,
        "line_total": 45.00,
        "is_rx_only": false
      }
    ],
    "rx_items_present": false,
    "discount_type": null,
    "discount_value": 0,
    "subtotal": 45.00,
    "gst_total": 4.82,
    "discount_amount": 0,
    "grand_total": 45.00,
    "expires_at": "2026-07-24T14:00:00Z"
  }
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 404 | `CART_NOT_FOUND` | cart_id not found |
| 410 | `CART_EXPIRED` | Cart has been abandoned |

---

### 3. Add Item to Cart

```
POST /api/v1/pharmacy/pos/cart/:cart_id/items
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 120 req/min

**Request Body (application/json):**

```json
{
  "product_id": "UUID - required",
  "batch_id": "UUID - optional; auto-FEFO if omitted",
  "quantity": "integer > 0 - required",
  "is_loose": "boolean - optional, default false"
}
```

**Success Response - 201 Created:**

```json
{
  "success": true,
  "data": {
    "item_id": "uuid",
    "product_name": "Paracetamol 500mg Tab",
    "batch_number": "BN25100",
    "expiry_date": "2027-06-30",
    "quantity": 2,
    "is_loose": false,
    "unit_price": 22.50,
    "gst_pct": 12,
    "line_total": 45.00,
    "gst_amount": 4.82,
    "cart_grand_total": 45.00
  }
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `INSUFFICIENT_STOCK` | Requested quantity exceeds batch stock |
| 400 | `CART_COMPLETED` | Cart is already checked out |
| 400 | `PRODUCT_EXPIRED` | All batches for product are expired |
| 404 | `PRODUCT_NOT_FOUND` | Product not in pharmacy inventory |

---

### 4. Update Cart Item

```
PATCH /api/v1/pharmacy/pos/cart/:cart_id/items/:item_id
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 120 req/min

**Request Body (application/json):**

```json
{
  "quantity": "integer > 0 - optional",
  "batch_id": "UUID - optional, change batch assignment",
  "is_loose": "boolean - optional"
}
```

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "item_id": "uuid",
    "quantity": 3,
    "line_total": 67.50,
    "cart_grand_total": 67.50
  }
}
```

---

### 5. Remove Item from Cart

```
DELETE /api/v1/pharmacy/pos/cart/:cart_id/items/:item_id
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 60 req/min

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "item_id": "uuid",
    "cart_grand_total": 0.00
  }
}
```

---

### 6. Clear Cart

```
DELETE /api/v1/pharmacy/pos/cart/:cart_id
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 30 req/min

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "cart_id": "uuid",
    "items_removed": 3,
    "status": "ACTIVE"
  }
}
```

---

### 7. Search Products for POS

```
POST /api/v1/pharmacy/pos/cart/:cart_id/search
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 120 req/min

**Request Body (application/json):**

```json
{
  "query": "string - required",
  "mode": "BARCODE | TEXT - required"
}
```

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "results": [
      {
        "product_id": "uuid",
        "name": "Paracetamol 500mg Tab",
        "manufacturer": "Cipla Ltd",
        "form": "TABLET",
        "pack_size": 15,
        "mrp": 22.50,
        "total_stock_units": 450,
        "is_rx_only": false,
        "is_loose_selling_enabled": false,
        "rack_locations": ["A1-03"],
        "available_batches": [
          {
            "batch_id": "uuid",
            "batch_number": "BN25100",
            "expiry_date": "2027-06-30",
            "quantity_current": 300,
            "is_fefo_first": true
          }
        ],
        "auto_add": false
      }
    ],
    "mode": "TEXT",
    "query": "para"
  }
}
```

---

### 8. Attach Customer to Cart

```
POST /api/v1/pharmacy/pos/cart/:cart_id/customer
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 60 req/min

**Request Body (application/json):**

```json
{
  "customer_phone": "string E.164 - required",
  "customer_name": "string max 100 - optional; creates new customer if phone not found"
}
```

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "customer_id": "uuid",
    "name": "Priya Sharma",
    "phone": "+919876000001",
    "is_new_customer": false,
    "outstanding_khata": 500.00
  }
}
```

---

### 9. Apply Discount

```
POST /api/v1/pharmacy/pos/cart/:cart_id/discount
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 30 req/min

**Request Body (application/json):**

```json
{
  "type": "FLAT_RS | PERCENTAGE - required",
  "value": "number > 0 - required"
}
```

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "discount_type": "PERCENTAGE",
    "discount_value": 10,
    "discount_amount": 45.00,
    "grand_total": 405.00
  }
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `DISCOUNT_EXCEEDS_LIMIT` | Discount > 30% of subtotal or > Rs 500 |
| 400 | `EMPTY_CART` | No items in cart |

---

### 10. Checkout (Finalize Sale)

```
POST /api/v1/pharmacy/pos/cart/:cart_id/checkout
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 30 req/min

**Request Body (application/json):**

```json
{
  "payment_method": "CASH | UPI | CARD | COD | CREDIT | INSURANCE_TPA - required",
  "amount_paid": "number ? 0 - required",
  "upi_reference": "string max 50 - optional, required if payment_method = UPI",
  "prescribing_doctor": "string max 200 - required if any cart item is_rx_only = true"
}
```

**Success Response - 201 Created:**

```json
{
  "success": true,
  "data": {
    "invoice_id": "uuid",
    "invoice_number": "INV-2026-07-000042",
    "cart_id": "uuid",
    "payment_method": "CASH",
    "amount_paid": 450.00,
    "change_due": 0.00,
    "grand_total": 450.00,
    "gst_breakdown": [
      { "slab": "12%", "taxable_amount": 321.43, "gst_amount": 38.57 }
    ],
    "invoice_pdf_url": "https://cdn.medmate.in/pharmacy/uuid/INV-2026-07-000042.pdf",
    "items_count": 3,
    "customer_name": "Priya Sharma",
    "completed_at": "2026-07-24T12:15:00Z"
  }
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `EMPTY_CART` | No items in cart |
| 400 | `RX_PRESCRIBER_REQUIRED` | Rx items present, `prescribing_doctor` missing |
| 400 | `CREDIT_REQUIRES_NAMED_CUSTOMER` | CREDIT payment but no customer attached |
| 400 | `INSUFFICIENT_STOCK` | Stock depleted between add and checkout (race condition) |
| 400 | `CART_EXPIRED` | Cart has expired |
| 409 | `CART_ALREADY_COMPLETED` | Cart already checked out |

---

## Data Models

### PosCart

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID | PK | Cart session ID |
| `pharmacy_id` | UUID | FK ? Pharmacy, NOT NULL | Owning pharmacy |
| `staff_id` | UUID | FK ? Staff, NOT NULL | Creating staff member |
| `customer_id` | UUID | FK ? Customer, nullable | Attached named customer |
| `customer_name` | VARCHAR(100) | nullable | Walk-in customer name |
| `customer_phone` | VARCHAR(20) | nullable | Walk-in phone |
| `prescribing_doctor` | VARCHAR(200) | nullable | Doctor name for Rx items |
| `discount_type` | ENUM | nullable | FLAT_RS / PERCENTAGE |
| `discount_value` | NUMERIC(10,2) | default 0 | Discount value entered |
| `discount_amount` | NUMERIC(10,2) | computed | Actual discount applied (Rs) |
| `subtotal` | NUMERIC(12,2) | computed | Sum of line_totals |
| `gst_total` | NUMERIC(12,2) | computed | Sum of gst_amounts |
| `grand_total` | NUMERIC(12,2) | computed | subtotal - discount_amount |
| `status` | ENUM | NOT NULL, default ACTIVE | ACTIVE / COMPLETED / ABANDONED |
| `expires_at` | TIMESTAMPTZ | NOT NULL | 2 hours from last activity |
| `invoice_id` | UUID | FK ? Invoice, nullable | Linked invoice after checkout |
| `created_at` | TIMESTAMPTZ | NOT NULL | Cart creation time |
| `updated_at` | TIMESTAMPTZ | NOT NULL | Last cart mutation |

### PosCartItem

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID | PK | Cart item ID |
| `cart_id` | UUID | FK ? PosCart, NOT NULL | Parent cart |
| `product_id` | UUID | FK ? PharmacyProduct, NOT NULL | Product |
| `batch_id` | UUID | FK ? ProductBatch, NOT NULL | Assigned batch (FEFO or manual) |
| `quantity` | INTEGER | > 0, NOT NULL | Quantity (in units; packs if not loose) |
| `is_loose` | BOOLEAN | NOT NULL, default false | Loose selling flag |
| `unit_price` | NUMERIC(10,2) | NOT NULL | MRP per unit (or per loose unit) |
| `gst_pct` | NUMERIC(5,2) | NOT NULL | GST rate for this product |
| `line_subtotal` | NUMERIC(12,2) | computed | quantity - unit_price |
| `gst_amount` | NUMERIC(12,2) | computed | GST included in line_subtotal |
| `line_total` | NUMERIC(12,2) | computed | Same as line_subtotal (MRP inclusive) |
| `created_at` | TIMESTAMPTZ | NOT NULL | Item addition time |

---

## Acceptance Criteria

- [ ] Given a cart with one product (no `batch_id` specified), when the item is added, then the batch with the earliest `expiry_date` among active batches is auto-selected.
- [ ] Given a cart with one Rx item and `prescribing_doctor` omitted on checkout, then a 400 `RX_PRESCRIBER_REQUIRED` error is returned.
- [ ] Given `payment_method = CREDIT` and no customer attached, then a 400 `CREDIT_REQUIRES_NAMED_CUSTOMER` error is returned.
- [ ] Given `POST /cart/:id/discount` with `type=PERCENTAGE, value=35` when subtotal is Rs 1000, then a 400 `DISCOUNT_EXCEEDS_LIMIT` is returned (35% > 30% cap).
- [ ] Given `POST /cart/:id/checkout` is called successfully, then `ProductBatch.quantity_current` for all cart item batches is decremented by the correct quantities atomically.
- [ ] Given a cart with 2-hour inactivity, when `GET /cart/:id` is called, then `status = ABANDONED` and a 410 `CART_EXPIRED` is returned.
- [ ] Given `POST /cart/:id/search` with `mode=TEXT` and `query="A1-03"` (a rack code), then products assigned to that rack location are returned.
- [ ] Given `payment_method = CASH` and `amount_paid = 500` on a Rs 450 total, then `change_due = 50.00` in the checkout response.

---

## Dependencies

- **EPIC-006 / STORY-001-002 (Inventory & Batches):** Product lookup, FEFO batch selection, stock deduction.
- **EPIC-007 / STORY-002 (Invoice Management):** Checkout creates an Invoice record (defined in STORY-002).
- **EPIC-007 / STORY-003 (Khata):** CREDIT payment posts to Khata ledger.
- **EPIC-007 / STORY-005 (Offers):** Counter offers auto-apply at cart level.
- **EPIC-006 / STORY-003 (Rack Locations):** Rack code search in POS product lookup.

---

## Notes

- Cart state should be stored in the primary database (not Redis) to avoid data loss and allow multi-device access for the same session.
- Expired carts are soft-updated by a background job that runs every 15 minutes and sets `status = ABANDONED` on carts where `expires_at < now()`.
- The barcode field on `PharmacyProduct` is not defined in this epic; it is part of the Master Medicine Catalog (EPIC-001). The POS search delegates barcode lookup to the product service.
