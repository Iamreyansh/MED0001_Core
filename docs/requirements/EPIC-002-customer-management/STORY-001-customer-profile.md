# STORY-001: Customer Profile Management

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-001 |
| **Epic** | EPIC-002 - Customer Management |
| **Priority** | P0 |
| **Complexity** | M |
| **Status** | Draft |

---

## Overview

This story covers the self-service profile APIs available to authenticated customers as well as the admin-facing customer management capabilities. Customers can view and update their own profile fields, initiate an account deletion with a 30-day grace period, and manage their preferences. Admin staff can list customers with advanced filters, view full customer detail (including order stats, wallet balance, and loyalty info), flag accounts for trust & safety review, and send targeted notifications. The customer segment (NEW, REGULAR, LOYAL, VIP) is computed and updated automatically by the platform based on lifetime order and value thresholds.

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| customer | Write | Can view and update own profile; can request account deletion |
| admin_super | Admin | Full read/write access to all customer records and flags |
| admin_operations | Read | Can view customer profiles and order stats |
| admin_support | Read + Write | Can view customer profiles; can flag, remove flag, and notify customers |
| admin_finance | Read | Can view customer wallet balance and LTV for financial reporting |
| admin_compliance | Read | Can view flagged customers and compliance-related data |

## Business Rules

1. A customer's `name` is required to be set before placing their first order; at account creation (post-OTP verification) only `phone` is populated.
2. The `preferred_language` field accepts BCP-47 language codes for Indian languages: `en` (English), `kn` (Kannada), `hi` (Hindi), `ta` (Tamil), `te` (Telugu), `ml` (Malayalam), `mr` (Marathi). Any other value is rejected.
3. Account deletion (`DELETE /customers/me`) is a soft-delete with a 30-day grace period. The account's `deletion_requested_at` is set immediately. If the customer has any orders in `PENDING`, `CONFIRMED`, `OUT_FOR_DELIVERY` status, the deletion request is rejected with `409 ACTIVE_ORDERS_EXIST`.
4. During the 30-day grace period, the customer can cancel their deletion request by logging in and calling a `POST /customers/me/cancel-deletion` endpoint. After 30 days, a background job permanently anonymises the record (name ? "Deleted User", phone ? hashed value, all PII wiped).
5. Customer segments are auto-computed by a nightly job: NEW = 0 orders; REGULAR = 1-11 orders or LTV < Rs 5,000; LOYAL = 12-49 orders or LTV Rs 5,000-Rs 24,999; VIP = 50+ orders or LTV ? Rs 25,000. Segment changes are logged.
6. Admin flagging (`POST /admin/customers/:id/flag`) requires a `reason` from an enum: `HIGH_CANCELLATION`, `FRAUD_SUSPICION`, `ABUSIVE_BEHAVIOUR`, `DUPLICATE_ACCOUNT`, `PAYMENT_DEFAULT`, `OTHER`. If `OTHER`, a `note` (max 500 chars) is mandatory.
7. Flagged customers are still able to place orders by default (flagging is for monitoring, not blocking). An admin can additionally block a customer's account via a separate `suspend` action (scoped to admin_super).
8. `POST /admin/customers/:id/notify` is rate-limited to 3 notifications per customer per 24-hour window to prevent spam. Notifications are sent via FCM/APNs or SMS depending on the customer's registered device tokens.

## API Endpoints

### 1. Get Own Profile

```
GET /api/v1/customers/me
```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 60 req/min per user

**Success Response - `200 OK`:**
```json
{
  "success": true,
  "data": {
    "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "phone": "+919876543210",
    "name": "Ramesh Kumar",
    "avatar_url": "https://cdn.namma-medmate.in/avatars/abc123.jpg",
    "date_of_birth": "1988-05-14",
    "gender": "MALE",
    "preferred_language": "kn",
    "segment": "LOYAL",
    "is_flagged": false,
    "wallet_balance": 125.50,
    "loyalty_points": 38,
    "loyalty_tier": "SILVER",
    "total_orders": 24,
    "created_at": "2025-01-10T06:30:00Z"
  },
  "meta": {}
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 401 | `UNAUTHORIZED` | Token missing or invalid |

---

### 2. Update Own Profile

```
PATCH /api/v1/customers/me
```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 20 req/min per user

**Request Body (`application/json`):**
```json
{
  "name": "string - optional, max:100",
  "avatar_url": "string - optional, valid HTTPS URL, max:512",
  "date_of_birth": "string - optional, ISO 8601 date (YYYY-MM-DD), must be in the past, customer must be ?13 years old",
  "gender": "string - optional, enum: MALE|FEMALE|OTHER|PREFER_NOT_TO_SAY",
  "preferred_language": "string - optional, enum: en|kn|hi|ta|te|ml|mr"
}
```

**Success Response - `200 OK`:**
```json
{
  "success": true,
  "data": {
    "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "phone": "+919876543210",
    "name": "Ramesh K.",
    "avatar_url": "https://cdn.namma-medmate.in/avatars/new.jpg",
    "date_of_birth": "1988-05-14",
    "gender": "MALE",
    "preferred_language": "kn",
    "updated_at": "2026-07-24T02:00:00Z"
  },
  "meta": {}
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `VALIDATION_ERROR` | Invalid field value (bad language code, future DOB, etc.) |
| 401 | `UNAUTHORIZED` | Token missing or invalid |

---

### 3. Request Account Deletion

```
DELETE /api/v1/customers/me
```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 3 req/day per user

**Request Body (`application/json`):**
```json
{
  "reason": "string - optional, max:500, customer's reason for leaving"
}
```

**Success Response - `200 OK`:**
```json
{
  "success": true,
  "data": {
    "message": "Account deletion requested. Your account will be permanently deleted on 2026-08-23T02:00:00Z unless you cancel this request.",
    "deletion_scheduled_at": "2026-08-23T02:00:00Z",
    "cancel_before": "2026-08-23T02:00:00Z"
  },
  "meta": {}
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 401 | `UNAUTHORIZED` | Token missing or invalid |
| 409 | `ACTIVE_ORDERS_EXIST` | Customer has orders in PENDING / CONFIRMED / OUT_FOR_DELIVERY status |
| 409 | `DELETION_ALREADY_REQUESTED` | Deletion already pending |

---

### 4. List Customers (Admin)

```
GET /api/v1/admin/customers
```

**Authentication:** Bearer JWT - `admin_super`, `admin_operations`, `admin_support`, `admin_finance`, `admin_compliance`
**Rate Limit:** 30 req/min per user

**Query Parameters:**
| Param | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| page | integer | No | 1 | Page number |
| limit | integer | No | 20 | Results per page, max 100 |
| sort | string | No | created_at | Sort field: created_at \| name \| total_orders \| total_ltv |
| order | string | No | desc | asc \| desc |
| search | string | No | - | Search by name, phone, or email (partial match) |
| segment | string | No | - | Filter: NEW \| REGULAR \| LOYAL \| VIP |
| is_flagged | boolean | No | - | Filter flagged customers |
| city | string | No | - | Filter by city (case-insensitive) |
| export | boolean | No | false | If true, returns CSV download link instead of JSON list |

**Success Response - `200 OK`:**
```json
{
  "success": true,
  "data": [
    {
      "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
      "phone": "+919876543210",
      "name": "Ramesh Kumar",
      "city": "Bengaluru",
      "segment": "LOYAL",
      "is_flagged": false,
      "total_orders": 24,
      "total_ltv": 8400.00,
      "cancel_rate": 0.04,
      "created_at": "2025-01-10T06:30:00Z",
      "last_order_at": "2026-07-20T15:30:00Z"
    }
  ],
  "meta": {
    "page": 1,
    "limit": 20,
    "total": 4820,
    "has_next": true
  }
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 401 | `UNAUTHORIZED` | Token missing or invalid |
| 403 | `FORBIDDEN` | Role lacks `customers:read` permission |

---

### 5. Get Customer Detail (Admin)

```
GET /api/v1/admin/customers/:id
```

**Authentication:** Bearer JWT - `admin_super`, `admin_operations`, `admin_support`, `admin_finance`
**Rate Limit:** 30 req/min per user

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| :id | UUID | Customer ID |

**Success Response - `200 OK`:**
```json
{
  "success": true,
  "data": {
    "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "phone": "+919876543210",
    "name": "Ramesh Kumar",
    "avatar_url": "https://cdn.namma-medmate.in/avatars/abc123.jpg",
    "date_of_birth": "1988-05-14",
    "gender": "MALE",
    "preferred_language": "kn",
    "segment": "LOYAL",
    "is_flagged": false,
    "flag_reason": null,
    "created_at": "2025-01-10T06:30:00Z",
    "order_stats": {
      "total_orders": 24,
      "completed_orders": 23,
      "cancelled_orders": 1,
      "total_ltv": 8400.00,
      "cancel_rate": 0.04,
      "avg_order_value": 350.00,
      "last_order_at": "2026-07-20T15:30:00Z"
    },
    "wallet": {
      "balance": 125.50,
      "lifetime_credited": 600.00,
      "lifetime_debited": 474.50
    },
    "loyalty": {
      "tier": "SILVER",
      "points_balance": 38,
      "points_earned_lifetime": 84,
      "dispute_count": 0
    }
  },
  "meta": {}
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 401 | `UNAUTHORIZED` | Token missing or invalid |
| 403 | `FORBIDDEN` | Role lacks `customers:read` permission |
| 404 | `CUSTOMER_NOT_FOUND` | No customer with the given ID |

---

### 6. Flag Customer (Admin)

```
POST /api/v1/admin/customers/:id/flag
```

**Authentication:** Bearer JWT - `admin_super`, `admin_support`
**Rate Limit:** 20 req/min per user

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| :id | UUID | Customer ID to flag |

**Request Body (`application/json`):**
```json
{
  "reason": "string - required, enum: HIGH_CANCELLATION|FRAUD_SUSPICION|ABUSIVE_BEHAVIOUR|DUPLICATE_ACCOUNT|PAYMENT_DEFAULT|OTHER",
  "note": "string - required when reason is OTHER, max:500"
}
```

**Success Response - `200 OK`:**
```json
{
  "success": true,
  "data": {
    "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "is_flagged": true,
    "flag_reason": "FRAUD_SUSPICION",
    "flagged_by": "admin-staff-id",
    "flagged_at": "2026-07-24T02:00:00Z"
  },
  "meta": {}
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `VALIDATION_ERROR` | Invalid reason enum or missing note for OTHER |
| 401 | `UNAUTHORIZED` | Token missing or invalid |
| 403 | `FORBIDDEN` | Role lacks `customers:notify` permission |
| 404 | `CUSTOMER_NOT_FOUND` | Customer not found |
| 409 | `ALREADY_FLAGGED` | Customer is already flagged |

---

### 7. Remove Customer Flag (Admin)

```
DELETE /api/v1/admin/customers/:id/flag
```

**Authentication:** Bearer JWT - `admin_super`, `admin_support`
**Rate Limit:** 20 req/min per user

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| :id | UUID | Customer ID |

**Success Response - `200 OK`:**
```json
{
  "success": true,
  "data": {
    "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "is_flagged": false
  },
  "meta": {}
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 401 | `UNAUTHORIZED` | Token missing or invalid |
| 403 | `FORBIDDEN` | Role not permitted |
| 404 | `CUSTOMER_NOT_FOUND` | Customer not found |
| 409 | `NOT_FLAGGED` | Customer is not currently flagged |

---

### 8. Notify Customer (Admin)

```
POST /api/v1/admin/customers/:id/notify
```

**Authentication:** Bearer JWT - `admin_super`, `admin_support`
**Rate Limit:** 20 req/min per user; max 3 notifications per customer per 24 hours

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| :id | UUID | Customer ID |

**Request Body (`application/json`):**
```json
{
  "channel": "string - required, enum: PUSH|SMS|BOTH",
  "title": "string - required for PUSH, max:65",
  "body": "string - required, max:255",
  "deep_link": "string - optional, valid deep link URL for the app (e.g. medmate://orders/123)"
}
```

**Success Response - `200 OK`:**
```json
{
  "success": true,
  "data": {
    "notification_id": "n1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "channel": "PUSH",
    "delivered": true,
    "queued_at": "2026-07-24T02:00:00Z"
  },
  "meta": {}
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `VALIDATION_ERROR` | Missing required fields or invalid channel |
| 401 | `UNAUTHORIZED` | Token missing or invalid |
| 403 | `FORBIDDEN` | Role lacks `customers:notify` permission |
| 404 | `CUSTOMER_NOT_FOUND` | Customer not found |
| 429 | `NOTIFICATION_RATE_LIMITED` | 3 notifications already sent to this customer in the past 24 hours |

---

## Data Models

### Customer

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | UUID | PK, auto-gen | Primary key |
| phone | VARCHAR(15) | UNIQUE, NOT NULL | E.164 format - login identifier |
| name | VARCHAR(100) | nullable | Full display name; required before first order |
| avatar_url | VARCHAR(512) | nullable | HTTPS URL to profile picture |
| date_of_birth | DATE | nullable | Must be in the past; age ? 13 |
| gender | VARCHAR(25) | nullable | MALE \| FEMALE \| OTHER \| PREFER_NOT_TO_SAY |
| preferred_language | VARCHAR(5) | default 'en' | BCP-47: en \| kn \| hi \| ta \| te \| ml \| mr |
| segment | VARCHAR(10) | NOT NULL, default 'NEW' | NEW \| REGULAR \| LOYAL \| VIP |
| is_flagged | BOOLEAN | NOT NULL, default false | Trust & safety flag |
| flag_reason | VARCHAR(30) | nullable | Enum reason for flag |
| flag_note | TEXT | nullable | Free-text note for OTHER reason |
| flagged_by | UUID | FK ? admin_staff.id, nullable | Admin who set the flag |
| flagged_at | TIMESTAMPTZ | nullable | When the flag was set |
| total_orders | INTEGER | NOT NULL, default 0 | Denormalised order count |
| total_ltv | NUMERIC(12,2) | NOT NULL, default 0 | Lifetime value in INR |
| cancel_rate | NUMERIC(5,4) | NOT NULL, default 0 | Fraction of orders cancelled |
| dispute_count | INTEGER | NOT NULL, default 0 | Total disputes raised |
| deletion_requested_at | TIMESTAMPTZ | nullable | When account deletion was requested |
| deleted_at | TIMESTAMPTZ | nullable | Soft delete / anonymisation timestamp |
| device_tokens | TEXT[] | default '{}' | FCM/APNs push tokens |
| created_at | TIMESTAMPTZ | NOT NULL, default NOW() | Account creation timestamp |
| updated_at | TIMESTAMPTZ | NOT NULL | Last profile update |

## Acceptance Criteria

- [ ] Given an authenticated customer, when `GET /customers/me` is called, then the response includes `phone`, `name`, `segment`, `wallet_balance`, `loyalty_points`, and `loyalty_tier`.
- [ ] Given a customer profile update with `preferred_language: "de"`, when `PATCH /customers/me` is called, then `400 VALIDATION_ERROR` is returned as `de` is not in the supported language list.
- [ ] Given a customer with an order in `CONFIRMED` status, when `DELETE /customers/me` is called, then `409 ACTIVE_ORDERS_EXIST` is returned and `deletion_requested_at` is NOT set.
- [ ] Given an admin with `customers:read` permission, when `GET /admin/customers` is called with `segment=VIP&is_flagged=false`, then only non-flagged VIP customers are returned in the paginated response.
- [ ] Given an admin flags a customer with `reason: OTHER` but no `note`, then `400 VALIDATION_ERROR` is returned with a message indicating that `note` is required for the OTHER reason.
- [ ] Given `POST /admin/customers/:id/notify` has been called 3 times for customer X within the last 24 hours, when a 4th call is made, then `429 NOTIFICATION_RATE_LIMITED` is returned.
- [ ] Given a customer's `total_orders` reaches 12 via the nightly segment recomputation job, then their `segment` is updated from `REGULAR` to `LOYAL`.

## Dependencies

- EPIC-001 / STORY-001 - Customer created (phone-only record) on first OTP verification
- EPIC-002 / STORY-003 - Wallet balance surfaced in the profile response
- EPIC-002 / STORY-005 - Loyalty tier surfaced in the profile response
- EPIC-003 - Order completion triggers segment recomputation

## Notes

- `total_orders`, `total_ltv`, and `cancel_rate` are denormalised for performance. They are updated via DB triggers or event-driven consumers on order status changes.
- Avatar uploads should be handled via a pre-signed S3 URL flow (separate media upload story), not by accepting a URL in the PATCH body directly. For now, accept HTTPS URLs as a placeholder.
- The account deletion anonymisation job runs nightly; it selects customers where `deletion_requested_at + 30 days < NOW()` and `deleted_at IS NULL`.
