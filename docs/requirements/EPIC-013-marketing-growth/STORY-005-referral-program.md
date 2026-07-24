# STORY-005: Referral Program

| Field | Value |
|---|---|
| Story ID | EPIC-013-STORY-005 |
| Epic | EPIC-013 Marketing and Growth |
| Title | Referral Program |
| Priority | P0 |
| Status | Planned |
| Created | 2026-07-24 |
| Last Updated | 2026-07-24 |

---

## Overview

The Referral Program powers Namma MedMate's word-of-mouth acquisition loop. Each registered customer receives a unique referral code that, when applied by a new user at signup, links the two accounts. Both the referrer and referee receive Rs 100 wallet credit once the referee completes their first DELIVERED order. The program is entirely platform-funded and configurable by `admin_super`. Deep links ensure the referee's app install is pre-filled with the referral code, minimising friction. Admin HQ tracks program economics via a dedicated dashboard including referral CAC, conversion rates, and a top-referrers leaderboard.

---

## User Roles

| Role | Capability |
|---|---|
| `admin_super` | View all referral data; update program settings; pause program |
| `admin_operations` | View referral table and leaderboard; read-only on settings |
| `customer` | View own referral info; share link; apply referral code |

---

## Business Rules

1. **Referral code uniqueness** - each customer has exactly one referral code, generated at account creation, which is alphanumeric, uppercase, 8 characters long, and globally unique.
2. **Reward trigger** - Rs 100 wallet credit is credited to both referrer and referee only after the referee's first order reaches status `DELIVERED`; it is not triggered by order placement or payment.
3. **One-time application** - a referral code can be applied only once per customer account (cannot apply multiple codes or change after signup); attempting to apply again returns `REFERRAL_ALREADY_APPLIED`.
4. **No self-referral** - a customer cannot apply their own referral code; the system validates `referral_code != current_customer.referral_code` and returns `REFERRAL_SELF_REFERRAL`.
5. **Signup-time only** - referral codes must be applied at account creation (`POST /api/v1/referral/apply`); they cannot be retroactively applied to an existing account.
6. **Reward expiry** - referral wallet credits expire 365 days from the date of credit; expired credits are written off.
7. **Platform-wide pause** - when `is_active = false` in program settings, new referral code applications are rejected with `REFERRAL_PROGRAM_PAUSED`; existing pending rewards are still processed.
8. **Referral CAC** - computed as `total_rewards_paid_rs / total_converted_referrals`; shown in admin KPI.
9. **Deep link format** - referral link is a branch.io (or equivalent) deep link that opens the app with the referral code pre-populated in the signup form.
10. **One referral per referee** - each new customer can be referred by at most one person.

---

## API Endpoints

### 1. Get My Referral Info (Customer)

```
GET /api/v1/customers/me/referral
Authorization: Bearer JWT (customer)
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "referral_code": "PRIYA8K2",
    "referral_link": "https://app.nammamedmate.com/signup?ref=PRIYA8K2",
    "total_referrals": 12,
    "converted_referrals": 8,
    "total_earned_rs": 800,
    "pending_rewards_rs": 200,
    "earnings_stats": {
      "friends_joined": 12,
      "total_earned_rs": 800,
      "pending_rs": 200
    }
  }
}
```

---

### 2. Generate / Share Referral Link (Customer)

```
POST /api/v1/customers/me/referral/invite
Authorization: Bearer JWT (customer)
Content-Type: application/json
```

**Request Body**
```json
{
  "channel": "WHATSAPP"
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "referral_code": "PRIYA8K2",
    "referral_link": "https://app.nammamedmate.com/signup?ref=PRIYA8K2",
    "share_text": "Use my code PRIYA8K2 to get Rs 100 off your first order on Namma MedMate!",
    "channel": "WHATSAPP",
    "share_logged_at": "2026-07-24T10:00:00Z"
  }
}
```

---

### 3. Apply Referral Code (Customer - at Signup)

```
POST /api/v1/referral/apply
Authorization: Bearer JWT (customer)
Content-Type: application/json
```

**Request Body**
```json
{
  "referral_code": "PRIYA8K2",
  "referee_customer_id": "cust_uuid_new_001"
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "referral_code": "PRIYA8K2",
    "referee_customer_id": "cust_uuid_new_001",
    "referrer_customer_id": "cust_uuid_001",
    "status": "PENDING",
    "message": "Referral linked. Rs 100 credit will be applied after your first delivered order."
  }
}
```

**Error Responses**

| HTTP | Error Code | Description |
|---|---|---|
| 400 | `REFERRAL_CODE_NOT_FOUND` | Code does not exist |
| 400 | `REFERRAL_SELF_REFERRAL` | Referee is the referrer |
| 409 | `REFERRAL_ALREADY_APPLIED` | Referee already has a referral linked |
| 403 | `REFERRAL_PROGRAM_PAUSED` | Program is inactive |

---

### 4. Admin Referral Overview (Admin)

```
GET /api/v1/admin/referrals
Authorization: Bearer JWT (admin_super | admin_operations)
```

**Query Parameters**

| Parameter | Type | Description |
|---|---|---|
| `status` | string | `PENDING`, `CONVERTED`, `EXPIRED` |
| `page` | integer | Default 1 |
| `limit` | integer | Default 20 |

**Response 200**
```json
{
  "success": true,
  "data": {
    "chips": {
      "total_referrals": 4820,
      "converted_referrals": 2940,
      "pending_rewards_rs": 194000,
      "referral_cac_rs": 133,
      "referral_mrr_rs": 0
    },
    "top_referrers": [
      {
        "customer_id": "cust_uuid_001",
        "name": "Priya Sharma",
        "total_referrals": 48,
        "converted": 32,
        "total_earned_rs": 3200
      }
    ],
    "referrals": [
      {
        "id": "ref_uuid_001",
        "referrer_name": "Priya Sharma",
        "referee_name": "Ankit Gupta",
        "referee_phone": "+919xxxxxxxxx",
        "status": "CONVERTED",
        "reward_credited_at": "2026-07-20T14:00:00Z"
      }
    ]
  },
  "meta": { "page": 1, "limit": 20, "total": 4820 }
}
```

---

### 5. Get Program Settings (Admin)

```
GET /api/v1/admin/referrals/program
Authorization: Bearer JWT (admin_super | admin_operations)
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "reward_for_referrer_rs": 100,
    "reward_for_referee_rs": 100,
    "is_active": true,
    "reward_expiry_days": 365,
    "conditions": "Reward credited after referee's first DELIVERED order. One code per customer."
  }
}
```

---

### 6. Update Program Settings (Admin)

```
PATCH /api/v1/admin/referrals/program
Authorization: Bearer JWT (admin_super)
Content-Type: application/json
```

**Request Body**
```json
{
  "reward_for_referrer_rs": 150,
  "reward_for_referee_rs": 100,
  "is_active": true,
  "reward_expiry_days": 365,
  "conditions": "Updated conditions text."
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

**Error Responses**

| HTTP | Error Code | Description |
|---|---|---|
| 403 | `FORBIDDEN` | Only `admin_super` may update program settings |

---

## Data Model

### Referral

| Field | Type | Constraints | Description |
|---|---|---|---|
| `id` | UUID v4 | PK | Internal identifier |
| `referrer_customer_id` | UUID | FK ? customers | Who shared the code |
| `referee_customer_id` | UUID | FK ? customers, UNIQUE | Who used the code |
| `referral_code` | VARCHAR(8) | NOT NULL | Code that was applied |
| `status` | ENUM | DEFAULT PENDING | `PENDING`, `CONVERTED`, `EXPIRED` |
| `first_order_id` | UUID | NULLABLE FK ? orders | Qualifying first order |
| `referrer_reward_rs` | DECIMAL(8,2) | NULLABLE | Reward to referrer |
| `referee_reward_rs` | DECIMAL(8,2) | NULLABLE | Reward to referee |
| `reward_credited_at` | TIMESTAMPTZ | NULLABLE | When credits were issued |
| `created_at` | TIMESTAMPTZ | DEFAULT NOW() | Code applied at |

### ReferralProgramSettings

| Field | Type | Constraints | Description |
|---|---|---|---|
| `id` | UUID v4 | PK | Singleton record |
| `reward_for_referrer_rs` | DECIMAL(8,2) | NOT NULL | Rs credit to referrer |
| `reward_for_referee_rs` | DECIMAL(8,2) | NOT NULL | Rs credit to referee |
| `is_active` | BOOLEAN | DEFAULT true | Program on/off |
| `reward_expiry_days` | INTEGER | DEFAULT 365 | Days until reward expires |
| `conditions` | TEXT | NULLABLE | Customer-facing terms |
| `updated_by` | UUID | FK ? admin_users | Last modifier |
| `updated_at` | TIMESTAMPTZ | DEFAULT NOW() | Last update |

---

## Acceptance Criteria

1. Customer receives a unique 8-character referral code upon account creation.
2. Applying a valid referral code at signup returns HTTP 200 and creates a `PENDING` referral record.
3. Applying one's own referral code returns HTTP 400 `REFERRAL_SELF_REFERRAL`.
4. Attempting to apply a second referral code to an account that already has one returns HTTP 409 `REFERRAL_ALREADY_APPLIED`.
5. When referee's first order reaches DELIVERED, both referrer and referee receive Rs 100 wallet credit and referral status becomes CONVERTED.
6. When `is_active = false`, applying a referral code returns HTTP 403 `REFERRAL_PROGRAM_PAUSED`.
7. Admin overview KPI `referral_cac_rs = total_rewards_paid_rs / total_converted_referrals` matches arithmetic.
8. Top-referrers leaderboard lists customers sorted by `converted` count descending.
9. Referral wallet credits expire 365 days from credit date; expired balance shown separately.
10. Admin `admin_super` can update `reward_for_referrer_rs` and it applies to all future reward disbursements.

---

## Dependencies

| Dependency | Description |
|---|---|
| Customer Wallet / Finance Module | Wallet credit disbursement (Rs 100 each) |
| Order Module | Trigger on `order.status = DELIVERED` for referee's first order |
| Customer Auth | Account creation hook to link referral |
| Deep Link Service | Branch.io or equivalent for mobile referral links |
| Notification Engine | Notify referrer when reward is credited |

---

## Notes

- Referral CAC is platform's cheapest acquisition channel; program should be monitored for abuse (e.g. bulk account creation by one person). Fraud detection on referrals is in scope for EPIC-012 (Fraud).
- Referral code generation uses cryptographic random alphanumeric; birthday-collision probability at 100K customers is negligible with 8-char base-36 space (~2.8 trillion combinations).
- The share event logged by `POST /api/v1/customers/me/referral/invite` increments a `share_count` per channel for analytics.
