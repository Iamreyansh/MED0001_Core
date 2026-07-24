# STORY-006: Loyalty Program

| Field | Value |
|---|---|
| Story ID | EPIC-013-STORY-006 |
| Epic | EPIC-013 Marketing and Growth |
| Title | Loyalty Program |
| Priority | P0 |
| Status | Planned |
| Created | 2026-07-24 |
| Last Updated | 2026-07-24 |

---

## Overview

The Loyalty Program incentivises repeat purchase by awarding points for every completed order and allowing customers to redeem them as wallet credit at checkout. Customers earn 1 point per Rs 100 of item subtotal (not delivery fee, handling, or coupon discount) rounded down, and points are credited only after the order is DELIVERED. Points are redeemable at 1 point = Rs 1 wallet credit, capped at 20% of cart value per order, with a minimum redemption of 10 points. Tier progression (SILVER ? GOLD ? PLATINUM) is based on lifetime points earned, is permanent in v1 (no downgrade), and unlocks cosmetic benefits and potential future perks. Admins can configure earn rates and tier thresholds and manually adjust balances with an audit trail.

---

## User Roles

| Role | Capability |
|---|---|
| `admin_super` | Update program settings; manually adjust customer points |
| `admin_operations` | View loyalty overview; read-only on individual balances |
| `admin_finance` | View points liability and overview analytics |
| `customer` | View balance, tier, and transaction history; redeem at checkout |

---

## Business Rules

1. **Earn on item total only** - points are earned on `item_total` (sum of item prices); delivery fee, handling charges, and any coupon discount are excluded from the earn base.
2. **Earn rate** - 1 point per Rs 100 of eligible `item_total`, rounded down (e.g. Rs 580 earns 5 points, not 5.8).
3. **Post-delivery crediting** - points are credited to the customer's account only when `order.status = DELIVERED`; they are not credited at placement or payment.
4. **Cancellation reversal** - if an order is cancelled after points were credited (e.g. post-delivery claim scenario), the points are reversed via a `ADJUSTED` transaction with reason `ORDER_CANCELLED`.
5. **Redemption cap** - a customer may redeem at most `FLOOR(cart_total - 0.20)` points per order (20% of cart value); attempting to redeem more returns `EXCEEDS_REDEMPTION_CAP`.
6. **Minimum redemption** - minimum 10 points required per redemption; `INSUFFICIENT_POINTS_FOR_REDEMPTION` returned if fewer.
7. **Tier thresholds (lifetime earned)** - SILVER: 12 pts, GOLD: 50 pts, PLATINUM: 120 pts; tiers are assigned based on `points_earned_lifetime`, not balance.
8. **Permanent tier in v1** - tier never downgrades in v1 (even if balance drops to zero); tier upgrade triggers in-app notification.
9. **Points expiry** - points expire `points_expiry_days` days after being earned (configurable; default 365); expiry is processed nightly by a scheduled job.
10. **Admin adjustment** - `admin_super` can credit or debit points manually with a mandatory reason; recorded as `ADJUSTED` event in transaction history.

---

## API Endpoints

### 1. Get My Loyalty Status (Customer)

```
GET /api/v1/customers/me/loyalty
Authorization: Bearer JWT (customer)
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "tier": "GOLD",
    "points_balance": 68,
    "points_earned_lifetime": 94,
    "tier_thresholds": {
      "SILVER": 12,
      "GOLD": 50,
      "PLATINUM": 120
    },
    "points_to_next_tier": 26,
    "next_tier": "PLATINUM",
    "estimated_value_rs": 68
  }
}
```

---

### 2. Get Loyalty Transaction History (Customer)

```
GET /api/v1/customers/me/loyalty/transactions
Authorization: Bearer JWT (customer)
```

**Query Parameters**

| Parameter | Type | Description |
|---|---|---|
| `event_type` | string | `EARNED`, `REDEEMED`, `EXPIRED`, `ADJUSTED` |
| `page` | integer | Default 1 |
| `limit` | integer | Default 20 |

**Response 200**
```json
{
  "success": true,
  "data": {
    "transactions": [
      {
        "id": "lpt_uuid_001",
        "event_type": "EARNED",
        "points": 5,
        "order_id": "ord_uuid_001",
        "description": "Points earned on order #ORD-20260720-001234",
        "created_at": "2026-07-20T15:00:00Z"
      },
      {
        "id": "lpt_uuid_002",
        "event_type": "REDEEMED",
        "points": -20,
        "order_id": "ord_uuid_002",
        "description": "Redeemed 20 points (Rs 20 wallet credit)",
        "created_at": "2026-07-21T10:00:00Z"
      }
    ]
  },
  "meta": { "page": 1, "limit": 20, "total": 48 }
}
```

---

### 3. Redeem Points at Checkout (Customer)

```
POST /api/v1/customers/me/loyalty/redeem
Authorization: Bearer JWT (customer)
Content-Type: application/json
```

**Request Body**
```json
{
  "points_to_redeem": 20,
  "cart_id": "cart_uuid_001"
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "points_redeemed": 20,
    "wallet_credit_applied_rs": 20,
    "points_balance_after": 48,
    "redemption_transaction_id": "lpt_uuid_003"
  }
}
```

**Error Responses**

| HTTP | Error Code | Description |
|---|---|---|
| 400 | `INSUFFICIENT_POINTS` | Customer balance < `points_to_redeem` |
| 400 | `BELOW_MINIMUM_REDEMPTION` | `points_to_redeem` < 10 |
| 400 | `EXCEEDS_REDEMPTION_CAP` | Redemption > 20% of cart value |
| 404 | `CART_NOT_FOUND` | `cart_id` does not exist |

---

### 4. Get Loyalty Program Settings (Admin)

```
GET /api/v1/admin/loyalty/program
Authorization: Bearer JWT (admin_super | admin_operations | admin_finance)
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "earn_rate_rs_per_point": 100,
    "redemption_rate_rs_per_point": 1,
    "tier_thresholds": {
      "SILVER": 12,
      "GOLD": 50,
      "PLATINUM": 120
    },
    "max_redemption_pct_per_order": 20,
    "min_points_per_redemption": 10,
    "points_expiry_days": 365
  }
}
```

---

### 5. Update Loyalty Program Settings (Admin)

```
PATCH /api/v1/admin/loyalty/program
Authorization: Bearer JWT (admin_super)
Content-Type: application/json
```

**Request Body**
```json
{
  "earn_rate_rs_per_point": 100,
  "max_redemption_pct_per_order": 25,
  "points_expiry_days": 365
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "updated_at": "2026-07-24T10:00:00Z",
    "updated_by": "admin_uuid_001"
  }
}
```

---

### 6. Admin Loyalty Overview (Admin)

```
GET /api/v1/admin/loyalty/overview
Authorization: Bearer JWT (admin_super | admin_operations | admin_finance)
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "total_points_outstanding": 482000,
    "points_liability_rs": 482000,
    "avg_points_per_customer": 34,
    "tier_distribution": {
      "NONE": 8420,
      "SILVER": 3100,
      "GOLD": 980,
      "PLATINUM": 240
    },
    "points_earned_last_30d": 58400,
    "points_redeemed_last_30d": 22100,
    "points_expired_last_30d": 1800
  }
}
```

---

### 7. Admin Manual Point Adjustment (Admin)

```
POST /api/v1/admin/loyalty/customers/:customer_id/adjust
Authorization: Bearer JWT (admin_super)
Content-Type: application/json
```

**Request Body**
```json
{
  "points": 50,
  "reason": "Compensation for delayed order ORD-20260720-001234",
  "reference_order_id": "ord_uuid_001"
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "customer_id": "cust_uuid_001",
    "points_adjusted": 50,
    "points_balance_after": 118,
    "transaction_id": "lpt_uuid_004",
    "adjusted_by": "admin_uuid_001",
    "adjusted_at": "2026-07-24T11:00:00Z"
  }
}
```

**Error Responses**

| HTTP | Error Code | Description |
|---|---|---|
| 400 | `ADJUSTMENT_WOULD_EXCEED_BALANCE` | Negative adjustment exceeds current balance |
| 403 | `FORBIDDEN` | Only `admin_super` may adjust points |

---

## Data Model

### LoyaltyAccount

| Field | Type | Constraints | Description |
|---|---|---|---|
| `id` | UUID v4 | PK | Internal identifier |
| `customer_id` | UUID | FK ? customers, UNIQUE | One account per customer |
| `points_balance` | INTEGER | DEFAULT 0, >= 0 | Current redeemable balance |
| `points_earned_lifetime` | INTEGER | DEFAULT 0 | Lifetime earned (for tier calc) |
| `tier` | ENUM | DEFAULT NONE | `NONE`, `SILVER`, `GOLD`, `PLATINUM` |
| `updated_at` | TIMESTAMPTZ | DEFAULT NOW() | Last change |

### LoyaltyTransaction

| Field | Type | Constraints | Description |
|---|---|---|---|
| `id` | UUID v4 | PK | Internal identifier |
| `customer_id` | UUID | FK ? customers | Account owner |
| `event_type` | ENUM | NOT NULL | `EARNED`, `REDEEMED`, `EXPIRED`, `ADJUSTED` |
| `points` | INTEGER | NOT NULL | Positive = credit, negative = debit |
| `order_id` | UUID | NULLABLE FK ? orders | Linked order |
| `description` | TEXT | NOT NULL | Human-readable description |
| `expiry_date` | DATE | NULLABLE | When this batch expires |
| `adjusted_by` | UUID | NULLABLE FK ? admin_users | Admin who adjusted |
| `created_at` | TIMESTAMPTZ | DEFAULT NOW() | Transaction timestamp |

### LoyaltyProgramSettings (singleton)

| Field | Type | Constraints | Description |
|---|---|---|---|
| `id` | UUID v4 | PK | Singleton |
| `earn_rate_rs_per_point` | INTEGER | DEFAULT 100 | Rs spend per 1 point |
| `redemption_rate_rs_per_point` | DECIMAL(4,2) | DEFAULT 1.00 | Rs value per point |
| `tier_silver_pts` | INTEGER | DEFAULT 12 | SILVER threshold |
| `tier_gold_pts` | INTEGER | DEFAULT 50 | GOLD threshold |
| `tier_platinum_pts` | INTEGER | DEFAULT 120 | PLATINUM threshold |
| `max_redemption_pct_per_order` | INTEGER | DEFAULT 20 | % of cart value |
| `min_points_per_redemption` | INTEGER | DEFAULT 10 | Min points to redeem |
| `points_expiry_days` | INTEGER | DEFAULT 365 | Days until expiry |
| `updated_by` | UUID | FK ? admin_users | Last modifier |
| `updated_at` | TIMESTAMPTZ | DEFAULT NOW() | Last update |

---

## Acceptance Criteria

1. Customer earns 5 points on an item total of Rs 580 (floors `580/100 = 5`) after order DELIVERED.
2. Points are NOT credited at order placement; they appear in the account only after DELIVERED event.
3. Cancelled order correctly reverses previously credited points via an `ADJUSTED` transaction.
4. Redemption of 20 points on a Rs 200 cart (20% cap = 40 points, 20 ? 40) succeeds and applies Rs 20 wallet credit.
5. Redemption of 50 points on a Rs 200 cart returns `EXCEEDS_REDEMPTION_CAP` (20% cap = 40 points).
6. Redemption of 5 points returns `BELOW_MINIMUM_REDEMPTION` (minimum is 10).
7. Customer tier upgrades from SILVER to GOLD when `points_earned_lifetime` crosses 50; tier is updated immediately on point credit.
8. Tier does not downgrade even if customer redeems all points (balance drops to 0).
9. Admin manual adjustment credits 50 points correctly; transaction appears in customer history as `ADJUSTED`.
10. Points expired by nightly job appear as `EXPIRED` transactions; `points_balance` decreases accordingly.

---

## Dependencies

| Dependency | Description |
|---|---|
| Customer Wallet / Finance Module | Convert points to wallet credit on redemption |
| Order Module | `order.status = DELIVERED` trigger for point credit |
| Cart Module | `cart_id` and `cart_total` for redemption cap check |
| Notification Engine | Tier upgrade push notification |
| Scheduled Job Runner | Nightly points expiry job |

---

## Notes

- Points are tracked per-batch with individual expiry dates to implement FIFO expiry correctly (oldest points expire first on partial redemption).
- `points_earned_lifetime` only ever increases; it is used solely for tier computation, not for balance calculation.
- In v2, tier benefits (e.g. PLATINUM: free delivery, priority support) will be implemented; in v1 tier is cosmetic only.
- The `estimated_value_rs` returned in the loyalty status is `points_balance - redemption_rate_rs_per_point` (currently 1:1).
