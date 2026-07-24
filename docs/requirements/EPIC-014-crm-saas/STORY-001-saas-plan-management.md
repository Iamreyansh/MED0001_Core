# STORY-001: SaaS Plan Management

| Field | Value |
|---|---|
| Story ID | EPIC-014-STORY-001 |
| Epic | EPIC-014 CRM SaaS |
| Title | SaaS Plan Management |
| Priority | P0 |
| Status | Planned |
| Created | 2026-07-24 |
| Last Updated | 2026-07-24 |

---

## Overview

SaaS Plan Management defines and administers the Namma MedMate Pharmacy ERP subscription plan catalogue - including plan tiers, pricing, seat limits, module inclusions, and add-ons. Admin HQ uses this module to define what each plan offers, adjust pricing, and track subscriber counts and MRR per plan. Pharmacy owners view the plan catalogue from the pharmacy dashboard to make upgrade or subscription decisions. The module matrix is the master reference for feature access control: the system checks it at runtime to determine which ERP modules are accessible to a given pharmacy account based on their active plan.

---

## User Roles

| Role | Capability |
|---|---|
| `admin_super` | Full CRUD on plans and add-ons; update pricing/limits |
| `admin_finance` | Read plan economics; MRR per plan |
| `admin_operations` | Read plan catalogue; manage add-on attachments |
| `pharmacy_owner` | View available plans for upgrade/subscribe decisions |

---

## Business Rules

1. **Annual discount** - annual billing = monthly price - 10 (2 months free, ~17% off). This is computed dynamically; not stored as a separate field.
2. **Prorated upgrade billing** - when a pharmacy upgrades mid-cycle, a prorated credit for the remaining days on the current plan is applied, and the new plan is billed from the upgrade date.
3. **Downgrade at renewal** - plan downgrades are scheduled to take effect at the next renewal date, not immediately.
4. **Add-on billing cycle** - add-ons are always billed monthly regardless of whether the base plan is monthly or annual.
5. **Prorated add-on credit** - when an add-on is detached mid-cycle, a prorated credit is issued to the next invoice.
6. **Module matrix as access control source of truth** - every ERP API endpoint checks the module matrix to determine if the requesting pharmacy's plan includes the required module; this is enforced at the middleware layer.
7. **Subscriber count on plan list** - `subscriber_count` is a real-time count of ACTIVE subscriptions on that plan.
8. **Plan change audit log** - every plan price/limit change by admin is logged in the audit trail with the old and new values.
9. **Attach rate** - `attach_rate_pct = (accounts_with_addon / total_active_accounts) - 100` for each add-on.
10. **FREE plan** - the FREE plan has `price_monthly = 0` and cannot be deleted or deactivated; it is the default plan for new pharmacies.

---

## Plans and Add-Ons Catalogue

### Plans

| Plan | Monthly Price (Rs) | Seats | Invoice Cap/Month | Tier |
|---|---|---|---|---|
| FREE | 0 | 1 | 100 | Entry |
| STARTER | 699 | 2 | 500 | Core |
| RETAIL_PRO | 1,499 | 5 | Unlimited | Advanced |
| ENTERPRISE | Custom | Unlimited | Unlimited | Enterprise |

### Add-Ons

| Add-On ID | Name | Monthly Price (Rs) | Description |
|---|---|---|---|
| `E_INVOICE` | E-Invoice Integration | 199 | GST e-invoice generation |
| `WHATSAPP_INTEGRATION` | WhatsApp Business | 299 | WhatsApp prescription/order alerts |
| `EXTRA_SEAT` | Extra User Seat | 149 | Additional staff user |
| `API_ACCESS` | API Access | 499 | REST API for integrations |
| `BRANCH` | Branch Management | 399 | Multi-branch support |
| `ANALYTICS` | Advanced Analytics | 249 | Detailed ERP reporting |

---

## API Endpoints

### 1. List Plans (Admin)

```
GET /api/v1/admin/crm/plans
Authorization: Bearer JWT (admin_super | admin_finance | admin_operations)
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "plans": [
      {
        "plan_id": "plan_uuid_starter",
        "name": "STARTER",
        "price_monthly_rs": 699,
        "price_annual_rs": 6990,
        "seat_limit": 2,
        "invoice_cap_monthly": 500,
        "module_count": 8,
        "subscriber_count": 420,
        "mrr_rs": 293580
      },
      {
        "plan_id": "plan_uuid_retail_pro",
        "name": "RETAIL_PRO",
        "price_monthly_rs": 1499,
        "price_annual_rs": 14990,
        "seat_limit": 5,
        "invoice_cap_monthly": null,
        "module_count": 15,
        "subscriber_count": 180,
        "mrr_rs": 269820
      }
    ]
  }
}
```

---

### 2. Get Plan Detail (Admin)

```
GET /api/v1/admin/crm/plans/:id
Authorization: Bearer JWT (admin_super | admin_finance | admin_operations)
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "plan_id": "plan_uuid_starter",
    "name": "STARTER",
    "pricing": {
      "monthly_rs": 699,
      "annual_rs": 6990,
      "annual_savings_pct": 16.7
    },
    "limits": {
      "seats": 2,
      "invoices_per_month": 500
    },
    "included_modules": [
      "INVENTORY", "BILLING", "PURCHASE_ORDERS", "CUSTOMER_LEDGER",
      "REPORTS_BASIC", "PRESCRIPTION_MANAGEMENT", "EXPIRY_ALERTS", "STAFF_MANAGEMENT"
    ],
    "upgrade_path": "RETAIL_PRO",
    "subscriber_count": 420,
    "subscriber_list": {
      "data": [
        { "account_id": "acc_uuid_001", "pharmacy_name": "Apollo Pharmacy HSR", "since": "2026-01-15" }
      ],
      "meta": { "page": 1, "limit": 20, "total": 420 }
    }
  }
}
```

---

### 3. Update Plan (Admin)

```
PATCH /api/v1/admin/crm/plans/:id
Authorization: Bearer JWT (admin_super)
Content-Type: application/json
```

**Request Body**
```json
{
  "price_monthly_rs": 799,
  "seat_limit": 3,
  "invoice_cap_monthly": 600
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "plan_id": "plan_uuid_starter",
    "updated_at": "2026-07-24T10:00:00Z",
    "updated_by": "admin_uuid_001"
  }
}
```

**Error Responses**

| HTTP | Error Code | Description |
|---|---|---|
| 403 | `FORBIDDEN` | Only `admin_super` may update plan pricing |
| 404 | `PLAN_NOT_FOUND` | Plan ID does not exist |
| 422 | `CANNOT_MODIFY_FREE_PLAN_PRICE` | FREE plan price must remain 0 |

---

### 4. List Add-Ons Catalogue (Admin)

```
GET /api/v1/admin/crm/addons
Authorization: Bearer JWT (admin_super | admin_finance | admin_operations)
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "addons": [
      {
        "addon_id": "addon_uuid_einvoice",
        "name": "E_INVOICE",
        "price_monthly_rs": 199,
        "attach_rate_pct": 34.2,
        "mrr_rs": 47924
      },
      {
        "addon_id": "addon_uuid_wa",
        "name": "WHATSAPP_INTEGRATION",
        "price_monthly_rs": 299,
        "attach_rate_pct": 22.1,
        "mrr_rs": 39887
      }
    ]
  }
}
```

---

### 5. Attach Add-On to Account (Admin)

```
POST /api/v1/admin/crm/accounts/:account_id/addons/:addon_id
Authorization: Bearer JWT (admin_super | admin_operations)
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "account_id": "acc_uuid_001",
    "addon_id": "addon_uuid_einvoice",
    "effective_from": "2026-07-24T00:00:00Z",
    "next_billing_amount_rs": 199,
    "message": "Add-on activated immediately. Billed on next invoice cycle."
  }
}
```

**Error Responses**

| HTTP | Error Code | Description |
|---|---|---|
| 409 | `ADDON_ALREADY_ATTACHED` | Add-on already active on account |
| 404 | `ACCOUNT_NOT_FOUND` | Account ID does not exist |

---

### 6. Detach Add-On from Account (Admin)

```
DELETE /api/v1/admin/crm/accounts/:account_id/addons/:addon_id
Authorization: Bearer JWT (admin_super | admin_operations)
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "account_id": "acc_uuid_001",
    "addon_id": "addon_uuid_einvoice",
    "detached_at": "2026-07-24T10:30:00Z",
    "prorated_credit_rs": 89,
    "message": "Add-on detached. Prorated credit of Rs 89 applied to next invoice."
  }
}
```

---

### 7. Get Module Matrix (Admin)

```
GET /api/v1/admin/crm/module-matrix
Authorization: Bearer JWT (admin_super | admin_operations | admin_finance)
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "modules": [
      {
        "module_id": "mod_inventory",
        "module_name": "Inventory Management",
        "group": "CORE",
        "available_on": ["FREE", "STARTER", "RETAIL_PRO", "ENTERPRISE"]
      },
      {
        "module_id": "mod_analytics_adv",
        "module_name": "Advanced Analytics",
        "group": "ANALYTICS",
        "available_on": ["RETAIL_PRO", "ENTERPRISE"]
      }
    ]
  }
}
```

---

### 8. Get Plans for Pharmacy (Pharmacy-Facing)

```
GET /api/v1/pharmacy/subscription/plans
Authorization: Bearer JWT (pharmacy_owner)
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "current_plan": "STARTER",
    "plans": [
      {
        "plan_id": "plan_uuid_starter",
        "name": "STARTER",
        "price_monthly_rs": 699,
        "price_annual_rs": 6990,
        "seat_limit": 2,
        "included_modules": ["INVENTORY", "BILLING", "PURCHASE_ORDERS"],
        "is_current": true
      },
      {
        "plan_id": "plan_uuid_retail_pro",
        "name": "RETAIL_PRO",
        "price_monthly_rs": 1499,
        "price_annual_rs": 14990,
        "seat_limit": 5,
        "included_modules": ["INVENTORY", "BILLING", "PURCHASE_ORDERS", "ADVANCED_ANALYTICS"],
        "is_current": false,
        "upgrade_cta": "Upgrade Now"
      }
    ]
  }
}
```

---

## Data Model

### Plan

| Field | Type | Constraints | Description |
|---|---|---|---|
| `id` | UUID v4 | PK | Internal identifier |
| `name` | ENUM | UNIQUE, NOT NULL | `FREE`, `STARTER`, `RETAIL_PRO`, `ENTERPRISE` |
| `price_monthly_rs` | DECIMAL(10,2) | NOT NULL | Monthly price in Rs |
| `seat_limit` | INTEGER | NULLABLE | Max staff users (null = unlimited) |
| `invoice_cap_monthly` | INTEGER | NULLABLE | Max invoices per month (null = unlimited) |
| `is_active` | BOOLEAN | DEFAULT true | Soft availability flag |
| `created_at` | TIMESTAMPTZ | DEFAULT NOW() | Creation timestamp |
| `updated_at` | TIMESTAMPTZ | DEFAULT NOW() | Last update |

### Addon

| Field | Type | Constraints | Description |
|---|---|---|---|
| `id` | UUID v4 | PK | Internal identifier |
| `name` | ENUM | UNIQUE, NOT NULL | Add-on identifier |
| `price_monthly_rs` | DECIMAL(8,2) | NOT NULL | Monthly add-on price |
| `description` | TEXT | NULLABLE | Human-readable description |
| `is_active` | BOOLEAN | DEFAULT true | Availability flag |

### AccountAddon

| Field | Type | Constraints | Description |
|---|---|---|---|
| `account_id` | UUID | FK ? crm_accounts | Subscriber account |
| `addon_id` | UUID | FK ? addons | Add-on |
| `effective_from` | TIMESTAMPTZ | NOT NULL | Activation date |
| `detached_at` | TIMESTAMPTZ | NULLABLE | Deactivation date |
| PRIMARY KEY | `(account_id, addon_id)` | | Composite key |

### ModuleMatrix

| Field | Type | Constraints | Description |
|---|---|---|---|
| `id` | UUID v4 | PK | Internal identifier |
| `module_id` | VARCHAR(50) | UNIQUE, NOT NULL | Module code |
| `module_name` | VARCHAR(100) | NOT NULL | Display name |
| `group` | VARCHAR(50) | NOT NULL | Grouping (CORE, ANALYTICS, etc.) |
| `plan_ids` | UUID[] | NOT NULL | Plans where available |

---

## Acceptance Criteria

1. Admin lists all plans and sees correct `subscriber_count` and `mrr_rs` per plan.
2. Annual price = monthly price - 10 for every plan; `annual_savings_pct ? 16.7%`.
3. Admin updates STARTER plan price to Rs 799; change is audit-logged with old value Rs 699 and new value Rs 799.
4. Attempting to change FREE plan price returns `CANNOT_MODIFY_FREE_PLAN_PRICE`.
5. Attaching an add-on to an account that already has it returns HTTP 409 `ADDON_ALREADY_ATTACHED`.
6. Detaching an add-on mid-cycle returns a prorated credit amount > 0.
7. Module matrix correctly shows which plans include each module.
8. Pharmacy-facing plan list shows `is_current: true` for the pharmacy's active plan and `upgrade_cta` on higher tiers.
9. `attach_rate_pct` for E_INVOICE matches `(accounts_with_einvoice / total_active_accounts) - 100`.
10. Plan change by admin is recorded in the account's activity timeline.

---

## Dependencies

| Dependency | Description |
|---|---|
| CRM Accounts | `account_id` linkage for addon attachment |
| Subscription Module (STORY-002) | Billing cycle and proration logic |
| Billing Module (STORY-003) | Invoice generation on add-on attach/detach |
| Access Control Middleware | Module matrix enforcement |

---

## Notes

- ENTERPRISE plan pricing is custom and stored with `price_monthly_rs = 0` and a flag `is_custom_pricing = true`; actual pricing is managed in contract documents.
- Module matrix changes (adding/removing modules from plans) take effect on the next API restart or cache refresh (TTL 5 minutes).
