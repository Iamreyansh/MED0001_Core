# STORY-001: Coupon Management

| Field | Value |
|---|---|
| Story ID | EPIC-013-STORY-001 |
| Epic | EPIC-013 Marketing and Growth |
| Title | Coupon Management |
| Priority | P0 |
| Status | Planned |
| Created | 2026-07-24 |
| Last Updated | 2026-07-24 |

---

## Overview

Coupon Management provides platform-wide promo code creation, administration, and customer-facing validation for Namma MedMate orders. Admins in Admin HQ define coupon types (percentage off, flat rupee discount, free delivery), set budget envelopes, scope by customer segment or order type, and monitor redemption economics in real time. Customers apply coupons during checkout through a validate endpoint that checks eligibility in a single round-trip. Budget guard-rails automatically pause coupons when spend reaches the configured cap, and admin gets daily burn notifications to stay on top of marketing spend. All coupon activity is audit-logged for finance reconciliation.

---

## User Roles

| Role | Capability |
|---|---|
| `admin_super` | Full CRUD; delete coupons; pause/resume; view economics |
| `admin_operations` | Create, edit, pause/resume coupons; view analytics |
| `admin_finance` | Read-only; view budget spend and ROAS |
| `customer` | Validate coupon before checkout; list available coupons |

---

## Business Rules

1. **One coupon per order** - only a single coupon code may be applied per order; attempting to apply a second returns `COUPON_ALREADY_APPLIED`.
2. **Validation gate** - before applying, the platform checks: coupon status = ACTIVE, not expired (`valid_until` ? now), budget not exhausted (`budget_used` < `budget_total`), customer has not exceeded `max_per_user`, cart total ? `min_order_value`, and if segment-scoped the customer belongs to the segment.
3. **Budget auto-pause** - when `budget_used` reaches `budget_total`, the coupon status transitions to PAUSED automatically and `admin_operations` receives an in-app + email notification.
4. **Daily budget-burn notification** - a scheduled job at 09:00 IST sends each admin a digest of coupons with >70% budget consumed that day.
5. **Deletion rule** - a coupon with `redemptions_count > 0` cannot be deleted; instead it can only be expired (`status = EXPIRED`). Coupons with zero redemptions may be hard-deleted.
6. **Code uniqueness** - coupon codes are stored uppercase, trimmed, and must be globally unique across all statuses; duplicate code creation returns `COUPON_CODE_EXISTS`.
7. **Immutable fields** - `code` and `type` cannot be changed after creation via PATCH; all other fields are editable.
8. **RX-only coupons** - when `is_rx_orders_only = true`, the coupon is valid only if the order contains at least one prescription medicine; validation returns `COUPON_RX_ONLY` otherwise.
9. **First-order coupons** - when `is_first_order_only = true`, validation checks that the customer has zero delivered orders; returns `COUPON_FIRST_ORDER_ONLY` on failure.
10. **PERCENTAGE cap** - for `PERCENTAGE` type coupons, the actual discount applied is `MIN(cart_total - value / 100, max_discount_cap)`.

---

## API Endpoints

### 1. List Coupons (Admin)

```
GET /api/v1/admin/coupons
Authorization: Bearer JWT (admin_super | admin_operations | admin_finance)
```

**Query Parameters**

| Parameter | Type | Description |
|---|---|---|
| `status` | string | Filter: `ACTIVE`, `PAUSED`, `EXPIRED` |
| `type` | string | Filter: `PERCENTAGE`, `FLAT_RS`, `FREE_DELIVERY` |
| `page` | integer | Default 1 |
| `limit` | integer | Default 20, max 100 |
| `sort` | string | Field to sort by (e.g. `created_at`, `redemptions`) |
| `order` | string | `asc` or `desc` |

**Response 200**
```json
{
  "success": true,
  "data": {
    "chips": {
      "active_count": 12,
      "total_redemptions": 4580,
      "discount_spend_rs": 91600,
      "marketing_spend_rs": 150000
    },
    "coupons": [
      {
        "code": "NAMMA25",
        "type": "PERCENTAGE",
        "value": 25,
        "scope": "ALL_ORDERS",
        "min_order": 199,
        "max_discount": 100,
        "budget_total": 50000,
        "budget_used": 18250,
        "redemptions": 730,
        "status": "ACTIVE",
        "is_rx_specific": false,
        "valid_from": "2026-07-01T00:00:00Z",
        "valid_until": "2026-07-31T23:59:59Z"
      }
    ]
  },
  "meta": {
    "page": 1,
    "limit": 20,
    "total": 45
  }
}
```

---

### 2. Create Coupon (Admin)

```
POST /api/v1/admin/coupons
Authorization: Bearer JWT (admin_super | admin_operations)
Content-Type: application/json
```

**Request Body**
```json
{
  "code": "FLAT50",
  "type": "FLAT_RS",
  "value": 50,
  "min_order_value": 399,
  "max_discount_cap": 50,
  "max_redemptions_total": 5000,
  "max_per_user": 1,
  "budget_total": 250000,
  "segment_ids": ["seg_uuid_loyal"],
  "is_first_order_only": false,
  "is_rx_orders_only": false,
  "valid_from": "2026-08-01T00:00:00Z",
  "valid_until": "2026-08-31T23:59:59Z",
  "description": "Rs 50 flat off on orders above Rs 399",
  "terms": "Valid once per user. Platform funded."
}
```

**Response 201**
```json
{
  "success": true,
  "data": {
    "id": "cpn_uuid_001",
    "code": "FLAT50",
    "type": "FLAT_RS",
    "status": "ACTIVE",
    "created_at": "2026-07-24T07:30:00Z",
    "created_by": "admin_uuid_001"
  }
}
```

**Error Responses**

| HTTP | Error Code | Description |
|---|---|---|
| 409 | `COUPON_CODE_EXISTS` | Code already used by another coupon |
| 422 | `INVALID_DATE_RANGE` | `valid_from` is after `valid_until` |
| 422 | `INVALID_VALUE` | Value ? 0 or percentage > 100 |
| 403 | `FORBIDDEN` | Insufficient role |

---

### 3. Get Coupon Detail (Admin)

```
GET /api/v1/admin/coupons/:code
Authorization: Bearer JWT (admin_super | admin_operations | admin_finance)
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "id": "cpn_uuid_001",
    "code": "NAMMA25",
    "type": "PERCENTAGE",
    "value": 25,
    "status": "ACTIVE",
    "budget_total": 50000,
    "budget_used": 18250,
    "redemptions_count": 730,
    "economics": {
      "discount_per_redemption_rs": 25,
      "revenue_attributed_rs": 292000,
      "roas": 15.98
    },
    "redemptions_daily": [
      { "date": "2026-07-23", "count": 58 },
      { "date": "2026-07-24", "count": 42 }
    ],
    "budget_ring": {
      "used": 18250,
      "total": 50000,
      "pct_used": 36.5
    },
    "terms": "25% off subtotal, max Rs 100 per order. Platform funded.",
    "redeemed_by": {
      "data": [
        {
          "customer_id": "cust_uuid_001",
          "customer_name": "Priya Sharma",
          "order_id": "ord_uuid_001",
          "discount_applied_rs": 75,
          "redeemed_at": "2026-07-24T10:15:00Z"
        }
      ],
      "meta": { "page": 1, "limit": 20, "total": 730 }
    }
  }
}
```

---

### 4. Update Coupon (Admin)

```
PATCH /api/v1/admin/coupons/:code
Authorization: Bearer JWT (admin_super | admin_operations)
Content-Type: application/json
```

**Request Body** (all fields optional except restrictions noted)
```json
{
  "min_order_value": 299,
  "max_discount_cap": 120,
  "budget_total": 75000,
  "valid_until": "2026-09-30T23:59:59Z",
  "description": "Updated description",
  "terms": "Updated terms"
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "code": "NAMMA25",
    "updated_at": "2026-07-24T08:00:00Z"
  }
}
```

**Error Responses**

| HTTP | Error Code | Description |
|---|---|---|
| 400 | `IMMUTABLE_FIELD` | Attempt to change `code` or `type` |
| 404 | `COUPON_NOT_FOUND` | Code does not exist |

---

### 5. Toggle Coupon (Admin)

```
PATCH /api/v1/admin/coupons/:code/toggle
Authorization: Bearer JWT (admin_super | admin_operations)
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "code": "NAMMA25",
    "status": "PAUSED",
    "toggled_at": "2026-07-24T09:00:00Z"
  }
}
```

---

### 6. Delete Coupon (Admin)

```
DELETE /api/v1/admin/coupons/:code
Authorization: Bearer JWT (admin_super)
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "code": "NAMMA25",
    "action": "EXPIRED",
    "message": "Coupon has redemptions; status set to EXPIRED instead of deleted."
  }
}
```

**Error Responses**

| HTTP | Error Code | Description |
|---|---|---|
| 404 | `COUPON_NOT_FOUND` | Code does not exist |

---

### 7. Validate Coupon (Customer-Facing)

```
POST /api/v1/coupons/validate
Authorization: Bearer JWT (customer)
Content-Type: application/json
```

**Request Body**
```json
{
  "coupon_code": "NAMMA25",
  "cart_total": 580,
  "customer_id": "cust_uuid_001",
  "is_first_order": false,
  "pharmacy_id": "pharm_uuid_001"
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "valid": true,
    "discount_type": "PERCENTAGE",
    "discount_amount": 100,
    "applies_to": "SUBTOTAL",
    "error_code": null
  }
}
```

**Validation Error Response 200** (valid=false - not HTTP error)
```json
{
  "success": true,
  "data": {
    "valid": false,
    "discount_type": null,
    "discount_amount": 0,
    "applies_to": null,
    "error_code": "COUPON_MIN_ORDER_NOT_MET"
  }
}
```

**Possible `error_code` Values**

| Error Code | Meaning |
|---|---|
| `COUPON_NOT_FOUND` | Code does not exist |
| `COUPON_EXPIRED` | Past `valid_until` |
| `COUPON_PAUSED` | Coupon is paused |
| `COUPON_BUDGET_EXHAUSTED` | Budget fully consumed |
| `COUPON_PER_USER_LIMIT` | Customer exceeded `max_per_user` |
| `COUPON_MIN_ORDER_NOT_MET` | Cart total below `min_order_value` |
| `COUPON_SEGMENT_MISMATCH` | Customer not in targeted segment |
| `COUPON_FIRST_ORDER_ONLY` | Customer already has a delivered order |
| `COUPON_RX_ONLY` | Cart has no prescription items |

---

### 8. List Available Coupons (Customer-Facing)

```
GET /api/v1/coupons/available
Authorization: Bearer JWT (customer)
```

**Query Parameters**

| Parameter | Type | Description |
|---|---|---|
| `include_applied` | boolean | Include already-applied coupons |

**Response 200**
```json
{
  "success": true,
  "data": {
    "coupons": [
      {
        "code": "NAMMA25",
        "type": "PERCENTAGE",
        "value": 25,
        "description": "25% off your order, max Rs 100",
        "min_order_value": 199,
        "valid_until": "2026-07-31T23:59:59Z"
      },
      {
        "code": "FREEDEL",
        "type": "FREE_DELIVERY",
        "value": 0,
        "description": "Free delivery on your order",
        "min_order_value": 299,
        "valid_until": "2026-07-31T23:59:59Z"
      }
    ]
  },
  "meta": { "total": 2 }
}
```

---

## Data Model

### Coupon

| Field | Type | Constraints | Description |
|---|---|---|---|
| `id` | UUID v4 | PK | Internal identifier |
| `code` | VARCHAR(50) | UNIQUE, NOT NULL, UPPERCASE | Promo code string |
| `type` | ENUM | NOT NULL | `PERCENTAGE`, `FLAT_RS`, `FREE_DELIVERY` |
| `value` | DECIMAL(10,2) | NOT NULL | Discount value (pct or Rs) |
| `min_order_value` | DECIMAL(10,2) | DEFAULT 0 | Minimum cart total |
| `max_discount_cap` | DECIMAL(10,2) | NULLABLE | Max discount in Rs (for PERCENTAGE) |
| `budget_total` | DECIMAL(12,2) | NOT NULL | Total budget allocated |
| `budget_used` | DECIMAL(12,2) | DEFAULT 0 | Budget consumed to date |
| `redemptions_count` | INTEGER | DEFAULT 0 | Total redemptions |
| `max_redemptions_total` | INTEGER | NULLABLE | Max total redemptions |
| `max_per_user` | INTEGER | DEFAULT 1 | Max per customer |
| `segment_ids` | UUID[] | NULLABLE | Targeted segment IDs |
| `is_first_order_only` | BOOLEAN | DEFAULT false | First-order restriction |
| `is_rx_orders_only` | BOOLEAN | DEFAULT false | RX-order restriction |
| `valid_from` | TIMESTAMPTZ | NOT NULL | Coupon active from |
| `valid_until` | TIMESTAMPTZ | NOT NULL | Coupon expires at |
| `status` | ENUM | DEFAULT ACTIVE | `ACTIVE`, `PAUSED`, `EXPIRED` |
| `description` | TEXT | NULLABLE | Internal description |
| `terms` | TEXT | NULLABLE | Customer-facing terms |
| `created_by` | UUID | FK ? admin_users | Creator |
| `created_at` | TIMESTAMPTZ | DEFAULT NOW() | Creation timestamp |
| `updated_at` | TIMESTAMPTZ | DEFAULT NOW() | Last update |

### CouponRedemption

| Field | Type | Constraints | Description |
|---|---|---|---|
| `id` | UUID v4 | PK | Redemption record |
| `coupon_id` | UUID | FK ? coupons | Coupon redeemed |
| `order_id` | UUID | FK ? orders | Order where applied |
| `customer_id` | UUID | FK ? customers | Customer who redeemed |
| `discount_applied_rs` | DECIMAL(10,2) | NOT NULL | Actual discount applied |
| `redeemed_at` | TIMESTAMPTZ | DEFAULT NOW() | When applied |

---

## Acceptance Criteria

1. Admin can create a coupon with all valid fields; it appears in the list immediately with status ACTIVE.
2. Creating a coupon with a duplicate code returns HTTP 409 with `COUPON_CODE_EXISTS`.
3. Customer validate endpoint returns `valid: true` and correct `discount_amount` for a valid NAMMA25 (25% off Rs 580 cart = Rs 100 after cap).
4. Customer validate endpoint returns `valid: false` with `COUPON_MIN_ORDER_NOT_MET` when cart total < `min_order_value`.
5. When `budget_used` reaches `budget_total`, coupon status auto-transitions to PAUSED and an admin notification is sent.
6. Attempting to PATCH `code` or `type` returns HTTP 400 with `IMMUTABLE_FIELD`.
7. DELETE on a coupon with 0 redemptions removes it; DELETE on a coupon with redemptions changes status to EXPIRED and returns the appropriate message.
8. Customer available coupons list returns only ACTIVE, non-expired coupons applicable to the customer's segment.
9. Admin can toggle a coupon ACTIVE ? PAUSED ? ACTIVE; status reflects change in list view.
10. ROAS in coupon detail = revenue_attributed / discount_spend and matches arithmetic.

---

## Dependencies

| Dependency | Description |
|---|---|
| Customer Segments (STORY-004) | Segment membership check during validation |
| Order Module | `order_id` linkage in redemption records |
| Customer Auth | `customer_id` for per-user limit check |
| Notification Engine | Daily budget burn notifications |
| Finance Module | Budget reconciliation |

---

## Notes

- Coupon codes are stored and compared case-insensitively (uppercased on write, lowercased input accepted).
- `FREE_DELIVERY` coupon type zeroes the delivery_fee field on the order; no `max_discount_cap` applies.
- ROAS calculation requires order revenue attribution - order total is attributed if coupon was applied, regardless of whether coupon was the conversion driver.
- Segment IDs left as `null` or empty means coupon is open to all customers.
