# STORY-002: Subscription Management

| Field | Value |
|---|---|
| Story ID | EPIC-014-STORY-002 |
| Epic | EPIC-014 CRM SaaS |
| Title | Subscription Management |
| Priority | P0 |
| Status | Planned |
| Created | 2026-07-24 |
| Last Updated | 2026-07-24 |

---

## Overview

Subscription Management handles the full lifecycle of a pharmacy's SaaS subscription on Namma MedMate's ERP platform - from initial subscription through upgrades, downgrades, cancellations, and renewals. The system tracks subscription status across five states (ACTIVE, TRIAL, PAST_DUE, CANCELLED, EXPIRED) and enforces module access based on the current status and plan. Auto-renewal attempts payment three days before the renewal date, with failed payments triggering a dunning sequence. Admins can manually override subscriptions for partner deals, extended trials, or operational exceptions. All lifecycle transitions are audit-logged.

---

## User Roles

| Role | Capability |
|---|---|
| `admin_super` | Override subscription; extend trials; view all subscriptions |
| `admin_operations` | View all subscriptions; trigger manual renewal |
| `admin_finance` | View subscription billing status and renewals |
| `pharmacy_owner` | Subscribe, upgrade, downgrade, cancel own subscription; toggle auto-renew |

---

## Business Rules

1. **Default plan** - all new pharmacies are automatically placed on the FREE plan upon registration; no payment is required.
2. **Trial period** - a 14-day TRIAL on STARTER plan is triggered by admin or during the signup flow; no payment during trial.
3. **PAST_DUE grace period** - if auto-renewal payment fails, the subscription enters PAST_DUE status and remains accessible for 7 days (grace period).
4. **EXPIRED access lock** - after the 7-day grace period, status transitions to EXPIRED; all premium modules are immediately locked; core FREE plan modules remain accessible.
5. **Auto-renew timing** - auto-renewal payment is attempted 3 days before the renewal date; if the payment succeeds, the renewal date advances by the billing cycle length.
6. **Prorated upgrade** - upgrading mid-cycle gives a prorated credit for remaining days on the current plan; the new plan is billed from the upgrade date through the end of the current cycle.
7. **Downgrade scheduling** - downgrade requests are scheduled to take effect at the next renewal date; the pharmacy retains current plan access until then.
8. **Cancellation policy** - cancellation is scheduled at end of period (not immediate); a churn survey is sent upon cancellation.
9. **Admin override** - admin overrides bypass billing; they log `override_reason` and have a mandatory `override_expires_at` (max 90 days per override).
10. **Dunning triggers** - failed payment on auto-renew starts the dunning sequence (managed by STORY-003); dunning is separate from the grace period countdown.

---

## Subscription Status Transitions

| From | Event | To |
|---|---|---|
| (none) | Pharmacy registers | FREE/ACTIVE |
| FREE | Admin/flow grants trial | TRIAL |
| TRIAL | Trial period ends (no payment) | FREE/ACTIVE |
| TRIAL | Payment succeeds | ACTIVE |
| ACTIVE | Payment succeeds on renewal | ACTIVE (renewed) |
| ACTIVE | Payment fails on renewal | PAST_DUE |
| PAST_DUE | Payment succeeds (during grace) | ACTIVE |
| PAST_DUE | Grace period (7d) expires | EXPIRED |
| ACTIVE | Cancel request | ACTIVE (until end of period) ? CANCELLED |
| CANCELLED | End of billing period | EXPIRED |
| EXPIRED | Admin override or new payment | ACTIVE |

---

## API Endpoints

### 1. Subscribe to a Plan (Pharmacy)

```
POST /api/v1/pharmacy/subscription/subscribe
Authorization: Bearer JWT (pharmacy_owner)
Content-Type: application/json
```

**Request Body**
```json
{
  "plan_id": "plan_uuid_starter",
  "billing_cycle": "MONTHLY",
  "coupon_code": "SAAS20"
}
```

**Response 201**
```json
{
  "success": true,
  "data": {
    "subscription_id": "sub_uuid_001",
    "plan": "STARTER",
    "billing_cycle": "MONTHLY",
    "status": "ACTIVE",
    "renewal_date": "2026-08-24T00:00:00Z",
    "invoice_id": "inv_uuid_001",
    "amount_charged_rs": 699
  }
}
```

**Error Responses**

| HTTP | Error Code | Description |
|---|---|---|
| 409 | `ALREADY_SUBSCRIBED` | Pharmacy already has an active subscription |
| 404 | `PLAN_NOT_FOUND` | Plan ID does not exist |
| 400 | `INVALID_COUPON` | Coupon code is invalid or expired |
| 402 | `PAYMENT_FAILED` | Payment initiation failed |

---

### 2. Upgrade Plan (Pharmacy)

```
POST /api/v1/pharmacy/subscription/upgrade
Authorization: Bearer JWT (pharmacy_owner)
Content-Type: application/json
```

**Request Body**
```json
{
  "new_plan_id": "plan_uuid_retail_pro"
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "previous_plan": "STARTER",
    "new_plan": "RETAIL_PRO",
    "effective_immediately": true,
    "prorated_credit_rs": 233,
    "amount_charged_rs": 1266,
    "invoice_id": "inv_uuid_002",
    "new_renewal_date": "2026-08-24T00:00:00Z"
  }
}
```

**Error Responses**

| HTTP | Error Code | Description |
|---|---|---|
| 400 | `DOWNGRADE_NOT_ALLOWED` | Use downgrade endpoint instead |
| 402 | `PAYMENT_FAILED` | Prorated charge failed |
| 404 | `PLAN_NOT_FOUND` | Plan ID does not exist |

---

### 3. Downgrade Plan (Pharmacy)

```
POST /api/v1/pharmacy/subscription/downgrade
Authorization: Bearer JWT (pharmacy_owner)
Content-Type: application/json
```

**Request Body**
```json
{
  "new_plan_id": "plan_uuid_starter"
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "current_plan": "RETAIL_PRO",
    "scheduled_plan": "STARTER",
    "effective_date": "2026-08-24T00:00:00Z",
    "message": "Your plan will downgrade to STARTER at the end of your current billing cycle."
  }
}
```

---

### 4. Cancel Subscription (Pharmacy)

```
POST /api/v1/pharmacy/subscription/cancel
Authorization: Bearer JWT (pharmacy_owner)
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "subscription_id": "sub_uuid_001",
    "status": "ACTIVE",
    "cancels_at": "2026-08-24T00:00:00Z",
    "message": "Your subscription will remain active until 2026-08-24. No further charges."
  }
}
```

---

### 5. Get Current Subscription (Pharmacy)

```
GET /api/v1/pharmacy/subscription
Authorization: Bearer JWT (pharmacy_owner | pharmacy_staff)
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "subscription_id": "sub_uuid_001",
    "plan": "STARTER",
    "status": "ACTIVE",
    "billing_cycle": "MONTHLY",
    "price_monthly_rs": 699,
    "renewal_date": "2026-08-24T00:00:00Z",
    "auto_renew": true,
    "seat_usage": { "used": 2, "limit": 2 },
    "invoice_usage": { "used": 187, "limit": 500 },
    "modules_unlocked": ["INVENTORY", "BILLING", "PURCHASE_ORDERS", "CUSTOMER_LEDGER"],
    "addons": [
      { "name": "E_INVOICE", "price_monthly_rs": 199, "active_since": "2026-06-01T00:00:00Z" }
    ]
  }
}
```

---

### 6. Toggle Auto-Renew (Pharmacy)

```
PATCH /api/v1/pharmacy/subscription/auto-renew
Authorization: Bearer JWT (pharmacy_owner)
Content-Type: application/json
```

**Request Body**
```json
{ "enabled": false }
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "auto_renew": false,
    "message": "Auto-renew disabled. Your subscription will expire on 2026-08-24."
  }
}
```

---

### 7. Admin Override Subscription (Admin)

```
POST /api/v1/admin/crm/accounts/:account_id/subscription/override
Authorization: Bearer JWT (admin_super)
Content-Type: application/json
```

**Request Body**
```json
{
  "plan_id": "plan_uuid_retail_pro",
  "override_reason": "Partner deal - 3-month complimentary RETAIL_PRO",
  "override_expires_at": "2026-10-24T00:00:00Z"
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "account_id": "acc_uuid_001",
    "override_plan": "RETAIL_PRO",
    "override_expires_at": "2026-10-24T00:00:00Z",
    "override_by": "admin_uuid_001",
    "override_at": "2026-07-24T10:00:00Z"
  }
}
```

**Error Responses**

| HTTP | Error Code | Description |
|---|---|---|
| 403 | `FORBIDDEN` | Only `admin_super` may override |
| 422 | `OVERRIDE_DURATION_EXCEEDED` | `override_expires_at` more than 90 days from now |
| 404 | `ACCOUNT_NOT_FOUND` | Account does not exist |

---

## Data Model

### Subscription

| Field | Type | Constraints | Description |
|---|---|---|---|
| `id` | UUID v4 | PK | Internal identifier |
| `account_id` | UUID | FK ? crm_accounts, UNIQUE | One subscription per account |
| `plan_id` | UUID | FK ? plans | Current active plan |
| `scheduled_plan_id` | UUID | NULLABLE FK ? plans | Pending downgrade plan |
| `status` | ENUM | NOT NULL | `ACTIVE`, `TRIAL`, `PAST_DUE`, `CANCELLED`, `EXPIRED` |
| `billing_cycle` | ENUM | NOT NULL | `MONTHLY`, `ANNUAL` |
| `renewal_date` | DATE | NOT NULL | Next billing date |
| `trial_ends_at` | TIMESTAMPTZ | NULLABLE | Trial expiry (if in TRIAL) |
| `auto_renew` | BOOLEAN | DEFAULT true | Auto-renewal flag |
| `cancelled_at` | TIMESTAMPTZ | NULLABLE | When cancelled (end of period) |
| `expires_at` | TIMESTAMPTZ | NULLABLE | When access expires |
| `override_plan_id` | UUID | NULLABLE FK ? plans | Admin override plan |
| `override_expires_at` | TIMESTAMPTZ | NULLABLE | Override expiry |
| `override_reason` | TEXT | NULLABLE | Reason for override |
| `created_at` | TIMESTAMPTZ | DEFAULT NOW() | Subscription start |
| `updated_at` | TIMESTAMPTZ | DEFAULT NOW() | Last update |

---

## Acceptance Criteria

1. New pharmacy registration creates a FREE plan subscription automatically with status ACTIVE.
2. Subscribing to STARTER (MONTHLY) creates an invoice for Rs 699 and transitions subscription to ACTIVE.
3. Upgrading from STARTER to RETAIL_PRO mid-cycle produces a prorated charge; invoice shows the proration credit and net charge.
4. Downgrade request schedules the plan change at `renewal_date`; pharmacy retains RETAIL_PRO access until then.
5. Cancellation sets `cancels_at = renewal_date`; subscription remains ACTIVE until that date.
6. Auto-renew payment failure transitions subscription to PAST_DUE; modules remain accessible.
7. After 7 days in PAST_DUE without payment, subscription moves to EXPIRED and premium modules are locked.
8. Admin override grants RETAIL_PRO access for 90 days; override is recorded in account activity timeline.
9. Toggle auto-renew to `false` does not immediately cancel subscription; it sets `auto_renew = false`.
10. Dunning sequence (STORY-003) is triggered upon PAST_DUE transition.

---

## Dependencies

| Dependency | Description |
|---|---|
| Plan Management (STORY-001) | Plan catalogue and module matrix |
| Billing & Invoicing (STORY-003) | Invoice generation and dunning |
| Payment Gateway | Charge processing for subscribe/upgrade |
| Notification Engine | PAST_DUE, EXPIRED, renewal reminders |
| Access Control Middleware | Module lock on EXPIRED |
| Automation Engine | Dunning sequence trigger |

---

## Notes

- The subscription record is the single source of truth for module access; the middleware checks `status` and `plan_id` (or `override_plan_id` if active) on every ERP API call.
- Churn survey is sent asynchronously via the notification engine when `cancels_at` is set.
- TRIAL ? ACTIVE transition requires a payment method to be on file; if no payment method is added before trial ends, subscription reverts to FREE.
