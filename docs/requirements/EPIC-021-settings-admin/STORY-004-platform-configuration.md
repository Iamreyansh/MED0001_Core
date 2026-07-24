# STORY-004: Platform Configuration

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-004 |
| **Epic** | EPIC-021 - Settings & Platform Administration |
| **Priority** | P0 |
| **Complexity** | M |
| **Status** | Draft |

---

## Overview

This story provides a centralised key-value configuration store for all global platform parameters grouped by domain (Orders, Payments, Commissions, KYC, Rider). Admin staff with appropriate permissions can read all config values and `admin_super` can update them in bulk or individually. Every config change takes effect within 60 seconds (Redis TTL-based cache invalidation). Config values are strongly typed, validated on write, and their change history is retained in a `config_history` table for rollback reference. Some keys are marked `immutable` and cannot be changed in production without a code deployment.

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| admin_super | Admin | Can read and write all config keys |
| admin_operations | Read | Can read all config keys |
| admin_finance | Read | Can read payment and commission config keys |
| admin_support | Read | Can read order and payment config keys |
| admin_compliance | Read | Can read KYC config keys |

## Business Rules

1. Config keys are identified by a `domain.key` naming convention (e.g., `orders.min_order_value`). All keys are defined at platform initialisation and cannot be created via the API - only their values can be updated.
2. Only `admin_super` can modify config values. All other admin roles are read-only. Attempting a write with a non-super role returns `403 FORBIDDEN`.
3. Config values are strongly typed. Attempting to set a numeric key to a string value or a boolean key to an integer returns `400 VALIDATION_ERROR` with the expected type.
4. Some config keys are marked `immutable: true` and cannot be updated via the API in the `production` environment. These represent structural platform constants that require a code deployment to change (e.g., `orders.order_id_prefix`). Attempting to update an immutable key returns `422 CONFIG_KEY_IMMUTABLE`.
5. All config changes are automatically captured in the `config_history` table and the platform audit log. The history records `key`, `old_value`, `new_value`, `changed_by`, and `changed_at`.
6. Config values are cached in Redis with a 60-second TTL. When a value is updated via the API, the cache entry is immediately invalidated (deleted) so that the next read fetches the fresh value from the database. Application code always reads config from the Redis cache, falling back to the database on cache miss.
7. Bulk config update via `PATCH /admin/config` applies all changes in a single database transaction. If any key fails validation, the entire batch is rejected (no partial updates).
8. Config values are never `null`. Each key has a defined default value that is used if the key has not been explicitly set.

## API Endpoints

### 1. Get All Config

```
GET /api/v1/admin/config
```

**Authentication:** Bearer JWT - any admin role
**Rate Limit:** 30 req/min per user

**Query Parameters:**
| Param | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| domain | string | No | - | Filter by domain: orders \| payments \| commissions \| kyc \| rider |

**Success Response - `200 OK`:**
```json
{
  "success": true,
  "data": {
    "orders": {
      "min_order_value": {
        "key": "orders.min_order_value",
        "value": 49,
        "type": "integer",
        "unit": "INR",
        "immutable": false,
        "description": "Minimum cart value required to place an order",
        "updated_at": "2026-06-01T09:00:00Z"
      },
      "handling_fee": {
        "key": "orders.handling_fee",
        "value": 5,
        "type": "integer",
        "unit": "INR",
        "immutable": false,
        "description": "Fixed handling fee added to every order",
        "updated_at": "2025-06-01T09:00:00Z"
      },
      "delivery_fee": {
        "key": "orders.delivery_fee",
        "value": 25,
        "type": "integer",
        "unit": "INR",
        "immutable": false,
        "description": "Standard delivery fee per order",
        "updated_at": "2025-06-01T09:00:00Z"
      },
      "free_delivery_threshold": {
        "key": "orders.free_delivery_threshold",
        "value": 199,
        "type": "integer",
        "unit": "INR",
        "immutable": false,
        "description": "Order value at or above which delivery fee is waived",
        "updated_at": "2025-06-01T09:00:00Z"
      },
      "max_order_items": {
        "key": "orders.max_order_items",
        "value": 20,
        "type": "integer",
        "unit": "items",
        "immutable": false,
        "description": "Maximum number of distinct SKUs per order",
        "updated_at": "2025-06-01T09:00:00Z"
      },
      "order_sla_minutes": {
        "key": "orders.order_sla_minutes",
        "value": 60,
        "type": "integer",
        "unit": "minutes",
        "immutable": false,
        "description": "Target delivery time SLA from order confirmation",
        "updated_at": "2025-06-01T09:00:00Z"
      }
    },
    "payments": {
      "max_wallet_credit_per_transaction": {
        "key": "payments.max_wallet_credit_per_transaction",
        "value": 1000,
        "type": "integer",
        "unit": "INR",
        "immutable": false,
        "description": "Maximum single wallet credit an admin can issue",
        "updated_at": "2025-06-01T09:00:00Z"
      },
      "refund_window_days": {
        "key": "payments.refund_window_days",
        "value": 7,
        "type": "integer",
        "unit": "days",
        "immutable": false,
        "description": "Number of days post-delivery within which a refund can be initiated",
        "updated_at": "2025-06-01T09:00:00Z"
      },
      "cod_available": {
        "key": "payments.cod_available",
        "value": true,
        "type": "boolean",
        "unit": null,
        "immutable": false,
        "description": "Global toggle for Cash on Delivery payment option",
        "updated_at": "2025-06-01T09:00:00Z"
      }
    },
    "commissions": {
      "default_pharmacy_commission_pct": {
        "key": "commissions.default_pharmacy_commission_pct",
        "value": 8.5,
        "type": "decimal",
        "unit": "%",
        "immutable": false,
        "description": "Default platform commission % applied to pharmacy payouts",
        "updated_at": "2025-06-01T09:00:00Z"
      },
      "min_commission_pct": {
        "key": "commissions.min_commission_pct",
        "value": 3.0,
        "type": "decimal",
        "unit": "%",
        "immutable": false,
        "description": "Minimum allowable commission % for any pharmacy",
        "updated_at": "2025-06-01T09:00:00Z"
      },
      "max_commission_pct": {
        "key": "commissions.max_commission_pct",
        "value": 15.0,
        "type": "decimal",
        "unit": "%",
        "immutable": false,
        "description": "Maximum allowable commission % for any pharmacy",
        "updated_at": "2025-06-01T09:00:00Z"
      }
    },
    "kyc": {
      "kyc_auto_approve_enabled": {
        "key": "kyc.kyc_auto_approve_enabled",
        "value": false,
        "type": "boolean",
        "unit": null,
        "immutable": false,
        "description": "If true, pharmacies with clean document submissions are auto-approved",
        "updated_at": "2025-06-01T09:00:00Z"
      },
      "kyc_document_expiry_warning_days": {
        "key": "kyc.kyc_document_expiry_warning_days",
        "value": 30,
        "type": "integer",
        "unit": "days",
        "immutable": false,
        "description": "Days before document expiry to start sending renewal warnings",
        "updated_at": "2025-06-01T09:00:00Z"
      }
    },
    "rider": {
      "cod_in_hand_limit": {
        "key": "rider.cod_in_hand_limit",
        "value": 5000,
        "type": "integer",
        "unit": "INR",
        "immutable": false,
        "description": "Maximum COD cash a rider can hold before mandatory deposit",
        "updated_at": "2025-06-01T09:00:00Z"
      },
      "rider_assignment_timeout_seconds": {
        "key": "rider.rider_assignment_timeout_seconds",
        "value": 120,
        "type": "integer",
        "unit": "seconds",
        "immutable": false,
        "description": "Seconds to wait for a rider to accept an order before re-assigning",
        "updated_at": "2025-06-01T09:00:00Z"
      },
      "auto_assign_enabled": {
        "key": "rider.auto_assign_enabled",
        "value": true,
        "type": "boolean",
        "unit": null,
        "immutable": false,
        "description": "If true, orders are automatically assigned to available riders",
        "updated_at": "2025-06-01T09:00:00Z"
      }
    }
  },
  "meta": {}
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 401 | `UNAUTHORIZED` | Token missing or invalid |
| 403 | `FORBIDDEN` | Non-admin user |

---

### 2. Bulk Update Config

```
PATCH /api/v1/admin/config
```

**Authentication:** Bearer JWT - `admin_super`
**Rate Limit:** 10 req/min per user

**Request Body (`application/json`):**
```json
{
  "orders.min_order_value": 99,
  "orders.delivery_fee": 30,
  "payments.cod_available": false,
  "commissions.default_pharmacy_commission_pct": 9.0
}
```

**Success Response - `200 OK`:**
```json
{
  "success": true,
  "data": {
    "updated_keys": [
      "orders.min_order_value",
      "orders.delivery_fee",
      "payments.cod_available",
      "commissions.default_pharmacy_commission_pct"
    ],
    "updated_count": 4,
    "cache_invalidated": true,
    "effective_at": "2026-07-24T02:00:00Z"
  },
  "meta": {}
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `VALIDATION_ERROR` | Invalid key name or wrong value type |
| 401 | `UNAUTHORIZED` | Token missing or invalid |
| 403 | `FORBIDDEN` | Caller is not admin_super |
| 422 | `CONFIG_KEY_IMMUTABLE` | One or more keys are marked immutable in production |
| 422 | `CONFIG_KEY_NOT_FOUND` | One or more keys do not exist |

---

### 3. Get Single Config Value

```
GET /api/v1/admin/config/:key
```

**Authentication:** Bearer JWT - any admin role
**Rate Limit:** 60 req/min per user

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| :key | string | Config key in domain.key format (e.g., orders.delivery_fee) |

**Success Response - `200 OK`:**
```json
{
  "success": true,
  "data": {
    "key": "orders.delivery_fee",
    "value": 25,
    "type": "integer",
    "unit": "INR",
    "immutable": false,
    "description": "Standard delivery fee per order",
    "history": [
      {
        "old_value": 20,
        "new_value": 25,
        "changed_by": {
          "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
          "name": "Ayesha Siddiqui"
        },
        "changed_at": "2025-06-01T09:00:00Z",
        "notes": "Adjusted to cover last-mile cost increase"
      }
    ]
  },
  "meta": {}
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 401 | `UNAUTHORIZED` | Token missing or invalid |
| 403 | `FORBIDDEN` | Non-admin user |
| 404 | `CONFIG_KEY_NOT_FOUND` | Key does not exist |

---

## Data Models

### PlatformConfig

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| key | VARCHAR(100) | PK | `domain.key` format identifier |
| value | TEXT | NOT NULL | Stored as string; deserialised based on `type` |
| type | VARCHAR(10) | NOT NULL | integer \| decimal \| boolean \| string |
| unit | VARCHAR(20) | nullable | Display unit (INR, %, minutes, etc.) |
| domain | VARCHAR(20) | NOT NULL, indexed | orders \| payments \| commissions \| kyc \| rider |
| immutable | BOOLEAN | NOT NULL, default false | Cannot be updated via API in production |
| description | TEXT | NOT NULL | Human-readable description |
| updated_by | UUID | FK ? admin_staff.id, nullable | Who last updated the value |
| updated_at | TIMESTAMPTZ | NOT NULL | Last update timestamp |

### ConfigHistory

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | UUID | PK, auto-gen | Primary key |
| key | VARCHAR(100) | NOT NULL, indexed | Config key that was changed |
| old_value | TEXT | nullable | Previous value (null for initial set) |
| new_value | TEXT | NOT NULL | New value after the change |
| changed_by | UUID | FK ? admin_staff.id, NOT NULL | Admin who made the change |
| changed_at | TIMESTAMPTZ | NOT NULL, default NOW() | When the change was made |
| notes | TEXT | nullable | Optional notes about the reason for the change |

## Acceptance Criteria

- [ ] Given an `admin_super`, when `PATCH /admin/config` is called with `{ "orders.delivery_fee": 30 }`, then the config value is updated in the database, the Redis cache key is invalidated, and a `ConfigHistory` record is written with `old_value: "25"` and `new_value: "30"`.
- [ ] Given an `admin_operations` user, when `PATCH /admin/config` is called, then `403 FORBIDDEN` is returned and no values are changed.
- [ ] Given `PATCH /admin/config` is called with `{ "orders.delivery_fee": "thirty" }` (string instead of integer), then `400 VALIDATION_ERROR` is returned with a message indicating that `delivery_fee` expects an integer.
- [ ] Given `PATCH /admin/config` is called as a batch with 3 valid keys and 1 immutable key in production, then the entire batch is rejected with `422 CONFIG_KEY_IMMUTABLE` and none of the 3 valid keys are updated.
- [ ] Given `GET /admin/config/:key` is called for `orders.delivery_fee`, then the response includes the current `value`, `type`, `unit`, and a `history` array showing previous change records.
- [ ] Given the Redis cache for config is empty (cold start), when any service reads config, then it fetches from the database and re-populates the Redis cache with a 60-second TTL.
- [ ] Given `GET /admin/config?domain=payments`, then only config keys in the `payments` domain are returned.

## Dependencies

- EPIC-001 / STORY-003 - Admin auth for write access control
- EPIC-021 / STORY-003 - Audit log captures all config changes
- EPIC-000 / Infrastructure - Redis for 60-second config cache
- EPIC-002 / STORY-003 - `payments.max_wallet_credit_per_transaction` consumed by wallet credit endpoint

## Notes

- Initialise all config keys with default values via a database seed migration. The API only updates values; key creation is code-managed.
- For services that need to read config (e.g., order service reading `delivery_fee`), expose a shared `ConfigService` module that reads from Redis cache with DB fallback. Never hard-code config values in service logic.
- Type coercion on read: store all values as TEXT, cast to the appropriate type using the `type` column when serving responses. Validation on write should ensure the value can be successfully cast.
