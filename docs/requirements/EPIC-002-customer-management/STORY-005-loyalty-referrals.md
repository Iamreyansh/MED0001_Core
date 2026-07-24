# STORY-005: Loyalty Points & Referral Programme

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-005 |
| **Epic** | EPIC-002 - Customer Management |
| **Priority** | P1 |
| **Complexity** | L |
| **Status** | Draft |

---

## Overview

This story implements the Namma MedMate customer retention engine: a tiered loyalty programme (NONE ? SILVER ? GOLD ? PLATINUM) and a referral programme that rewards both the referrer and the new customer (referee) with Rs 100 wallet credit each after the referee's first delivered order. Loyalty points are earned at Rs 1 per Rs 100 spent and accumulate lifetime without expiry to drive long-term platform affinity. Tier progression and the referral reward are event-driven, triggered by order delivery events. Customers access their loyalty status and referral dashboard from the app.

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| customer | Read | Can view own loyalty status, tier progress, points history, and referral info |
| admin_super | Admin | Can view and manually adjust loyalty points for any customer |
| admin_support | Read | Can view loyalty and referral status for customer support |
| admin_finance | Read | Can view referral reward disbursement records |
| admin_operations | Read | Can view aggregate loyalty metrics |

## Business Rules

1. Loyalty points are awarded at a rate of 1 point per Rs 100 spent (rounded down). Points are awarded only after an order reaches `DELIVERED` status. Cancelled orders earn zero points; if a delivered order is later disputed and refunded, the points awarded for that order are reversed.
2. Tier thresholds are based on lifetime points earned (not balance, since points never expire): NONE = 0-11 points; SILVER = 12-49 points; GOLD = 50-119 points; PLATINUM = 120+ points. Tier evaluation runs on each point award event.
3. Once a customer reaches a tier, they do not drop back down even if they earn no new points. Tiers are one-way ratchets based on lifetime points, not current balance.
4. Points never expire and can be viewed but currently cannot be redeemed (redemption is a future roadmap feature). The points balance is informational - it drives tier assignment only.
5. Each customer is assigned a unique referral code at account creation. The code is a 7-character alphanumeric string (e.g., `MEDRAM7`), uppercase, unique platform-wide, generated as `MED` prefix + 4 random alphanumeric characters.
6. A referral code can only be applied by a customer at their first ever order placement using `POST /customers/me/referral/apply`. It cannot be applied after the first order has been placed or delivered. The endpoint returns `409 REFERRAL_ALREADY_USED` if the customer has already used or applied a code.
7. A customer cannot apply their own referral code. The server compares the referrer's customer ID with the referee's customer ID; if they match, `422 SELF_REFERRAL_NOT_ALLOWED` is returned.
8. The referral reward (Rs 100 wallet credit to both referrer and referee) is disbursed only after the referee's first order reaches `DELIVERED` status. If the first order is cancelled, no reward is issued. The system tracks a `pending_referral_reward` state between code application and order delivery.
9. Each customer can have at most one applied referral (one referee relationship). Each referral code application generates a `ReferralEvent` record that is updated from `PENDING` to `REWARDED` or `CANCELLED` based on the outcome of the first order.
10. The referral code is case-insensitive at application time. Server normalises to uppercase before lookup.

## API Endpoints

### 1. Get Loyalty Status

```
GET /api/v1/customers/me/loyalty
```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 30 req/min per user

**Success Response - `200 OK`:**
```json
{
  "success": true,
  "data": {
    "tier": "SILVER",
    "points_balance": 38,
    "points_earned_lifetime": 84,
    "tier_progress": {
      "current_tier": "SILVER",
      "next_tier": "GOLD",
      "points_for_next_tier": 50,
      "points_needed": 12,
      "progress_pct": 76
    },
    "tier_thresholds": {
      "NONE": { "min": 0, "max": 11 },
      "SILVER": { "min": 12, "max": 49 },
      "GOLD": { "min": 50, "max": 119 },
      "PLATINUM": { "min": 120, "max": null }
    }
  },
  "meta": {}
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 401 | `UNAUTHORIZED` | Token missing or invalid |

---

### 2. Get Loyalty Transaction History

```
GET /api/v1/customers/me/loyalty/transactions
```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 20 req/min per user

**Query Parameters:**
| Param | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| page | integer | No | 1 | Page number |
| limit | integer | No | 20 | Results per page, max 100 |
| order | string | No | desc | asc \| desc |
| type | string | No | - | Filter: EARN \| REVERSE |

**Success Response - `200 OK`:**
```json
{
  "success": true,
  "data": [
    {
      "id": "lp-tx-uuid-1",
      "type": "EARN",
      "points": 3,
      "points_balance_after": 38,
      "description": "Points for order #ORD-20260720-00123 (Rs 350 spent)",
      "reference_id": "ord-uuid-here",
      "created_at": "2026-07-20T18:00:00Z"
    },
    {
      "id": "lp-tx-uuid-2",
      "type": "REVERSE",
      "points": -2,
      "points_balance_after": 35,
      "description": "Points reversed for refunded order #ORD-20260701-00045",
      "reference_id": "ord-uuid-here",
      "created_at": "2026-07-02T10:00:00Z"
    }
  ],
  "meta": {
    "page": 1,
    "limit": 20,
    "total": 24,
    "has_next": true
  }
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 401 | `UNAUTHORIZED` | Token missing or invalid |

---

### 3. Get Referral Info

```
GET /api/v1/customers/me/referral
```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 30 req/min per user

**Success Response - `200 OK`:**
```json
{
  "success": true,
  "data": {
    "referral_code": "MEDRAM7",
    "referral_link": "https://namma-medmate.in/join?ref=MEDRAM7",
    "total_referrals": 5,
    "converted_referrals": 3,
    "pending_referrals": 1,
    "total_earned": 300.00,
    "pending_rewards": 100.00,
    "share_message": "Download Namma MedMate and get Rs 100 wallet credit on your first order! Use my referral code MEDRAM7. Link: https://namma-medmate.in/join?ref=MEDRAM7"
  },
  "meta": {}
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 401 | `UNAUTHORIZED` | Token missing or invalid |

---

### 4. Apply Referral Code

```
POST /api/v1/customers/me/referral/apply
```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 5 req/hour per user

**Request Body (`application/json`):**
```json
{
  "referrer_code": "string - required, 7-char alphanumeric referral code (case-insensitive)"
}
```

**Success Response - `200 OK`:**
```json
{
  "success": true,
  "data": {
    "referral_event_id": "re-uuid-here",
    "referrer_code": "MEDRAM7",
    "status": "PENDING",
    "message": "Referral code applied! You and Ramesh will each receive Rs 100 wallet credit after your first order is delivered.",
    "reward_amount": 100.00
  },
  "meta": {}
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `VALIDATION_ERROR` | Code is missing, empty, or not 7 alphanumeric characters |
| 401 | `UNAUTHORIZED` | Token missing or invalid |
| 404 | `REFERRAL_CODE_NOT_FOUND` | No customer with this referral code |
| 409 | `REFERRAL_ALREADY_USED` | This customer has already applied a referral code |
| 409 | `FIRST_ORDER_ALREADY_PLACED` | Customer has already placed their first order |
| 422 | `SELF_REFERRAL_NOT_ALLOWED` | Customer is trying to apply their own referral code |

---

## Data Models

### CustomerLoyalty

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | UUID | PK, auto-gen | Primary key |
| customer_id | UUID | FK ? customers.id, UNIQUE, NOT NULL | One loyalty record per customer |
| tier | VARCHAR(10) | NOT NULL, default 'NONE' | NONE \| SILVER \| GOLD \| PLATINUM |
| points_balance | INTEGER | NOT NULL, default 0, CHECK ? 0 | Current points balance (lifetime - reversed points) |
| points_earned_lifetime | INTEGER | NOT NULL, default 0 | Total points ever earned (used for tier calculation) |
| updated_at | TIMESTAMPTZ | NOT NULL | Last update time |

### LoyaltyTransaction

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | UUID | PK, auto-gen | Primary key |
| customer_id | UUID | FK ? customers.id, NOT NULL, indexed | Customer who earned/lost points |
| type | VARCHAR(10) | NOT NULL | EARN \| REVERSE |
| points | INTEGER | NOT NULL | Positive for EARN; negative for REVERSE |
| points_balance_after | INTEGER | NOT NULL | Balance snapshot after this transaction |
| description | VARCHAR(255) | NOT NULL | Human-readable reason |
| reference_id | UUID | nullable, indexed | Linked order ID |
| created_at | TIMESTAMPTZ | NOT NULL, default NOW() | Event timestamp (append-only) |

### CustomerReferral

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | UUID | PK, auto-gen | Primary key |
| customer_id | UUID | FK ? customers.id, UNIQUE, NOT NULL | Owner of the referral code |
| referral_code | VARCHAR(10) | UNIQUE, NOT NULL | Platform-unique referral code (e.g., MEDRAM7) |
| total_referrals | INTEGER | NOT NULL, default 0 | Total times this code was applied |
| converted_referrals | INTEGER | NOT NULL, default 0 | Times reward was disbursed (first order delivered) |
| total_earned | NUMERIC(10,2) | NOT NULL, default 0.00 | Total wallet credits earned via referrals |
| created_at | TIMESTAMPTZ | NOT NULL, default NOW() | Code generation time |

### ReferralEvent

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | UUID | PK, auto-gen | Primary key |
| referee_customer_id | UUID | FK ? customers.id, NOT NULL | Customer who applied the referral code |
| referrer_customer_id | UUID | FK ? customers.id, NOT NULL | Customer whose code was used |
| referral_code | VARCHAR(10) | NOT NULL | The code that was applied |
| status | VARCHAR(15) | NOT NULL, default 'PENDING' | PENDING \| REWARDED \| CANCELLED |
| first_order_id | UUID | FK ? orders.id, nullable | The qualifying first order |
| reward_amount | NUMERIC(8,2) | NOT NULL, default 100.00 | Reward per party in INR |
| referee_rewarded_at | TIMESTAMPTZ | nullable | When the referee received their wallet credit |
| referrer_rewarded_at | TIMESTAMPTZ | nullable | When the referrer received their wallet credit |
| created_at | TIMESTAMPTZ | NOT NULL, default NOW() | Code application time |
| updated_at | TIMESTAMPTZ | NOT NULL | Last status update |

## Acceptance Criteria

- [ ] Given a customer with `points_earned_lifetime: 50`, when `GET /customers/me/loyalty` is called, then `tier: "GOLD"` is returned and `tier_progress.next_tier` is `"PLATINUM"` with correct `points_needed` value.
- [ ] Given a customer applies referral code `MEDRAM7`, when the referee's first order reaches `DELIVERED` status, then within 5 minutes both the referrer and the referee receive a Rs 100 wallet credit (`reason: REFERRAL`), and the `ReferralEvent` status transitions from `PENDING` to `REWARDED`.
- [ ] Given a customer who has already applied a referral code, when `POST /customers/me/referral/apply` is called again, then `409 REFERRAL_ALREADY_USED` is returned.
- [ ] Given a customer tries to apply their own referral code (same customer ID), when `POST /customers/me/referral/apply` is called, then `422 SELF_REFERRAL_NOT_ALLOWED` is returned.
- [ ] Given a customer's first order is cancelled before delivery, when the cancellation event is processed, then the `ReferralEvent` status transitions from `PENDING` to `CANCELLED` and no wallet credits are issued.
- [ ] Given an order with value Rs 350 is delivered, when the loyalty points award job runs, then 3 points are credited (`floor(350/100) = 3`), a `LoyaltyTransaction` record with `type: EARN` and `points: 3` is created, and `points_earned_lifetime` is incremented.
- [ ] Given a customer at GOLD tier with 80 `points_earned_lifetime`, when a points reversal brings `points_balance` below the GOLD threshold but `points_earned_lifetime` remains 80, then the tier remains `GOLD` (tier is one-way ratchet based on lifetime points).

## Dependencies

- EPIC-001 / STORY-001 - Customer account and referral code created on first registration
- EPIC-002 / STORY-003 - Referral rewards and goodwill credits go to the wallet
- EPIC-003 - Order delivery event triggers both loyalty point award and referral reward disbursement

## Notes

- Referral code generation: `"MED" + 4-char random base-36 uppercase string`. Uniqueness must be checked at generation time with a retry loop (collision probability is negligible at current scale but must be handled).
- The referral reward disbursement is an async event consumer that listens on the order delivery event stream. It is not synchronous to the delivery API call.
- Tier changes should trigger a push notification to the customer (e.g., "Congratulations! You've reached GOLD tier!"). Notification dispatch is handled by EPIC-015.
- Consider adding point redemption (Rs 10 off per point) as a future Phase 2 feature after validating engagement metrics.
