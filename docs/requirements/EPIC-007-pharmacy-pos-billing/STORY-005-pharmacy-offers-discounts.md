# STORY-005: Pharmacy Offers & Discounts - Promotions and Coupon Engine

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-005 |
| **Epic** | EPIC-007 - Pharmacy POS & Billing |
| **Priority** | P2 |
| **Complexity** | M |
| **Status** | Draft |

---

## Overview

This story provides Growth-plan and above pharmacies with a promotions engine to create and manage discount offers that can be applied at the POS counter and/or shown on the customer app's online store. Pharmacists can define percentage or flat-Rs discounts scoped to all products, specific categories, or specific products. A coupon validation endpoint enables real-time eligibility checking at checkout. The admin console can view and audit all pharmacy offers for compliance monitoring.

---

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| `pharmacy_owner` | Full read + write | Create, edit, delete, toggle offers |
| `pharmacy_staff` | Read-only | View active offers; apply at POS (read-only) |
| `admin_super` | Read-only | View all pharmacy offers across platform |
| `admin_compliance` | Read-only | Compliance/abuse monitoring |
| `customer` | No direct API access | Sees offers on online store via customer app |

---

## Business Rules

1. **Growth plan gating.** All offers endpoints return 403 `PLAN_FEATURE_LOCKED` for Free and Starter pharmacies.
2. **Coupon code uniqueness per pharmacy.** `coupon_code` must be unique within a pharmacy. If `coupon_code` is omitted on creation, the system auto-generates a 6-character alphanumeric code (uppercase).
3. **Maximum discount constraints.** Percentage discount maximum is 50%. Flat-Rs discount maximum is Rs 1,000. Attempts to create offers exceeding these limits return `DISCOUNT_EXCEEDS_PLATFORM_LIMIT`.
4. **Multiple offers - highest discount wins.** When multiple applicable offers match a cart/product, the one producing the highest discount amount is applied. Stacking (applying multiple offers) is not supported in v1.
5. **Online vs counter scoping.** `is_online = true` makes the offer visible on the customer app with a "deal badge." `is_counter = true` makes it auto-applicable at the POS for qualifying products. Both flags can be true simultaneously for cross-channel offers.
6. **Expired offers are read-only.** Offers with `valid_until < today` are frozen; they cannot be edited, deleted, or toggled. Their redemption history is preserved. Attempting to edit an expired offer returns `OFFER_EXPIRED`.
7. **Non-zero redemption offers cannot be hard-deleted.** If an offer has `total_redemptions > 0`, `DELETE /offers/:offer_id` changes the status to `EXPIRED` (not a hard delete) and sets `valid_until = today`. A hard delete is only possible for offers with zero redemptions.
8. **Admin visibility.** The `admin_super` and `admin_compliance` roles can query a read-only endpoint to list all offers across all pharmacies for platform-level monitoring. This endpoint is at `/api/v1/admin/pharmacy-offers` and is out of scope for this story.
9. **Counter auto-apply.** When a counter offer's scope (`applies_to = PRODUCT`, `product_ids = [X]`) matches a product in the POS cart and the offer is active and within validity dates, the discount is automatically applied. No coupon code entry is required for counter offers.

---

## API Endpoints

### 1. List Offers

```
GET /api/v1/pharmacy/offers
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 60 req/min
**Plan:** Growth+

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `status` | enum | `ACTIVE` | `ACTIVE \| EXPIRED \| ALL` |
| `page` | integer | `1` | Page |
| `limit` | integer | `20` | Items per page |

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "kpi": {
      "active_count": 5,
      "total_redemptions": 284
    },
    "offers": [
      {
        "offer_id": "uuid",
        "title": "10% Off Antibiotics",
        "coupon_code": "ANTIBI10",
        "discount_type": "PERCENTAGE",
        "discount_value": 10,
        "applies_to": "CATEGORY",
        "category_names": ["Antibiotics"],
        "is_online": true,
        "is_counter": false,
        "valid_from": "2026-07-01",
        "valid_until": "2026-07-31",
        "max_redemptions": 500,
        "total_redemptions": 48,
        "is_active": true,
        "is_expired": false
      }
    ]
  },
  "meta": { "page": 1, "limit": 20, "total": 7 }
}
```

---

### 2. Create Offer

```
POST /api/v1/pharmacy/offers
```

**Authentication:** Bearer JWT - `pharmacy_owner`
**Rate Limit:** 20 req/min
**Plan:** Growth+

**Request Body (application/json):**

```json
{
  "title": "string max 200 - required",
  "coupon_code": "string 1-20 alphanumeric uppercase - optional; auto-generated if omitted",
  "discount_type": "PERCENTAGE | FLAT_RS - required",
  "discount_value": "number > 0 - required",
  "applies_to": "ALL | CATEGORY | PRODUCT - required",
  "category_ids": ["UUID - required if applies_to = CATEGORY"],
  "product_ids": ["UUID - required if applies_to = PRODUCT"],
  "is_online": "boolean - optional, default false",
  "is_counter": "boolean - optional, default false",
  "valid_from": "date YYYY-MM-DD - required",
  "valid_until": "date YYYY-MM-DD - required",
  "max_redemptions": "integer ? 0 - optional, default 0 (unlimited)"
}
```

**Success Response - 201 Created:**

```json
{
  "success": true,
  "data": {
    "offer_id": "uuid",
    "title": "10% Off Antibiotics",
    "coupon_code": "ANTIBI10",
    "discount_type": "PERCENTAGE",
    "discount_value": 10,
    "applies_to": "CATEGORY",
    "is_online": true,
    "is_counter": false,
    "valid_from": "2026-07-01",
    "valid_until": "2026-07-31",
    "is_active": true,
    "created_at": "2026-07-24T14:00:00Z"
  }
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `DISCOUNT_EXCEEDS_PLATFORM_LIMIT` | discount_value > 50% or > Rs 1000 |
| 400 | `INVALID_DATE_RANGE` | `valid_until ? valid_from` |
| 400 | `MISSING_SCOPE_IDS` | `applies_to = CATEGORY` but no `category_ids` |
| 403 | `PLAN_FEATURE_LOCKED` | Not on Growth+ |
| 409 | `COUPON_CODE_EXISTS` | coupon_code already exists for this pharmacy |

---

### 3. Update Offer

```
PATCH /api/v1/pharmacy/offers/:offer_id
```

**Authentication:** Bearer JWT - `pharmacy_owner`
**Rate Limit:** 20 req/min
**Plan:** Growth+

**Request Body (application/json):** Same optional fields as POST.

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "offer_id": "uuid",
    "title": "15% Off Antibiotics",
    "updated_at": "2026-07-24T14:10:00Z"
  }
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `OFFER_EXPIRED` | Offer has already expired |
| 404 | `OFFER_NOT_FOUND` | offer_id not found |

---

### 4. Toggle Offer Active/Inactive

```
PATCH /api/v1/pharmacy/offers/:offer_id/toggle
```

**Authentication:** Bearer JWT - `pharmacy_owner`
**Rate Limit:** 30 req/min
**Plan:** Growth+

**Request Body:** None.

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "offer_id": "uuid",
    "is_active": false,
    "toggled_at": "2026-07-24T14:15:00Z"
  }
}
```

---

### 5. Delete Offer

```
DELETE /api/v1/pharmacy/offers/:offer_id
```

**Authentication:** Bearer JWT - `pharmacy_owner`
**Rate Limit:** 10 req/min
**Plan:** Growth+

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "offer_id": "uuid",
    "action": "HARD_DELETED",
    "message": "Offer permanently deleted."
  }
}
```

**Conditional Response (when redemptions > 0):**

```json
{
  "success": true,
  "data": {
    "offer_id": "uuid",
    "action": "SET_EXPIRED",
    "message": "Offer had redemptions and has been expired instead of deleted.",
    "valid_until": "2026-07-24"
  }
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 404 | `OFFER_NOT_FOUND` | offer_id not found |

---

### 6. Validate Coupon Code at Checkout

```
POST /api/v1/pharmacy/offers/validate
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 60 req/min
**Plan:** Growth+

**Request Body (application/json):**

```json
{
  "coupon_code": "string - required",
  "cart_total": "number - required",
  "product_ids": ["UUID array - required; product IDs in the cart"]
}
```

**Success Response - 200 OK (Valid):**

```json
{
  "success": true,
  "data": {
    "is_valid": true,
    "offer_id": "uuid",
    "title": "10% Off Antibiotics",
    "discount_type": "PERCENTAGE",
    "discount_value": 10,
    "discount_amount": 42.00,
    "applies_to_description": "Applies to: Antibiotics category",
    "expires_on": "2026-07-31"
  }
}
```

**Success Response - 200 OK (Invalid):**

```json
{
  "success": true,
  "data": {
    "is_valid": false,
    "error_code": "COUPON_NOT_APPLICABLE",
    "message": "This coupon applies to Antibiotics only. No qualifying items in cart."
  }
}
```

**Possible `error_code` values in invalid response:**

| Code | Reason |
|------|--------|
| `COUPON_NOT_FOUND` | Code does not exist for this pharmacy |
| `COUPON_EXPIRED` | Offer `valid_until` has passed |
| `COUPON_NOT_ACTIVE` | Offer is toggled off |
| `COUPON_NOT_APPLICABLE` | Cart has no qualifying products/categories |
| `COUPON_LIMIT_REACHED` | `max_redemptions` exhausted |

---

## Data Models

### PharmacyOffer

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID | PK | Unique offer ID |
| `pharmacy_id` | UUID | FK ? Pharmacy, NOT NULL | Owning pharmacy |
| `title` | VARCHAR(200) | NOT NULL | Display title |
| `coupon_code` | VARCHAR(20) | NOT NULL, UNIQUE per pharmacy | Coupon code |
| `discount_type` | ENUM | NOT NULL | PERCENTAGE / FLAT_RS |
| `discount_value` | NUMERIC(8,2) | > 0, NOT NULL | Discount amount/percentage |
| `applies_to` | ENUM | NOT NULL | ALL / CATEGORY / PRODUCT |
| `scope_ids` | UUID[] | nullable | Category or product IDs |
| `is_online` | BOOLEAN | NOT NULL, default false | Visible on customer app |
| `is_counter` | BOOLEAN | NOT NULL, default false | Auto-apply at POS |
| `is_active` | BOOLEAN | NOT NULL, default true | Enabled/disabled |
| `valid_from` | DATE | NOT NULL | Start date |
| `valid_until` | DATE | NOT NULL | End date |
| `max_redemptions` | INTEGER | ? 0, default 0 | 0 = unlimited |
| `total_redemptions` | INTEGER | NOT NULL, default 0 | Running count |
| `created_at` | TIMESTAMPTZ | NOT NULL | Creation timestamp |
| `updated_at` | TIMESTAMPTZ | NOT NULL | Last update |

### OfferRedemption

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID | PK | Redemption record |
| `offer_id` | UUID | FK ? PharmacyOffer, NOT NULL | Redeemed offer |
| `pharmacy_id` | UUID | NOT NULL | Pharmacy context |
| `invoice_id` | UUID | FK ? Invoice, NOT NULL | Invoice where applied |
| `customer_id` | UUID | FK ? Customer, nullable | Customer (null for walk-in) |
| `discount_amount` | NUMERIC(10,2) | NOT NULL | Actual discount applied |
| `channel` | ENUM | NOT NULL | COUNTER / ONLINE |
| `created_at` | TIMESTAMPTZ | NOT NULL | Redemption timestamp |

---

## Acceptance Criteria

- [ ] Given a Free-plan pharmacy JWT, when `POST /api/v1/pharmacy/offers` is called, then a 403 `PLAN_FEATURE_LOCKED` error is returned.
- [ ] Given `POST /offers` with `discount_type=PERCENTAGE, discount_value=60`, then a 400 `DISCOUNT_EXCEEDS_PLATFORM_LIMIT` error is returned.
- [ ] Given `POST /offers` with `coupon_code` omitted, then the created offer has an auto-generated 6-character alphanumeric `coupon_code`.
- [ ] Given `POST /offers/validate` with a valid coupon code and matching cart products, then `is_valid = true` and `discount_amount` is correctly calculated.
- [ ] Given `POST /offers/validate` with a valid coupon code for CATEGORY=Antibiotics but no antibiotic products in the cart, then `is_valid = false` with code `COUPON_NOT_APPLICABLE`.
- [ ] Given `DELETE /offers/:offer_id` on an offer with `total_redemptions = 50`, then the offer is not hard-deleted; instead it is set to `EXPIRED` with `valid_until = today`.
- [ ] Given two active counter offers both applicable to a product, when the POS cart is computed, then only the higher-discount offer is applied.
- [ ] Given `PATCH /offers/:offer_id/toggle` on an active offer, then `is_active` flips to `false`; calling it again returns `is_active = true`.

---

## Dependencies

- **EPIC-007 / STORY-001 (POS):** Counter offers auto-apply during cart discount computation.
- **EPIC-002 (Online Store):** Online offers (`is_online = true`) are surfaced on the customer app product pages.
- **Plan Gating Middleware:** All endpoints validate Growth+ plan.

---

## Notes

- Counter offers are evaluated server-side on every cart state change. The cart response (STORY-001 GET /cart/:id) should include an `applied_offers` array listing any auto-applied counter offers.
- For v1, offer stacking is explicitly disabled. The platform may introduce tiered stacking logic in a future version.
- `OfferRedemption` records are created atomically with the invoice during checkout. If the checkout transaction rolls back, the redemption record must also be rolled back.
