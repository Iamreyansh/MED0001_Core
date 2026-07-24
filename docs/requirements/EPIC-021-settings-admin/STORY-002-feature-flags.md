# STORY-002: Feature Flag Management

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-002 |
| **Epic** | EPIC-021 - Settings & Platform Administration |
| **Priority** | P0 |
| **Complexity** | M |
| **Status** | Draft |

---

## Overview

This story implements a centrally managed feature flag system that allows `admin_super` to control the availability of platform features without code deployments. Flags follow a kill-switch model: a flag explicitly set to `enabled: false` overrides any partial rollout regardless of `rollout_percentage`. Gradual rollouts use a deterministic hash of the user ID to consistently include or exclude individual users in a percentage cohort. A public, unauthenticated endpoint allows frontend apps to poll for flag states every 60 seconds. All flag changes are audit-logged. Only `admin_super` can modify flags in the production environment.

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| admin_super | Admin | Full read/write over all feature flags |
| admin_operations | Read | Can view flag states for operational visibility |
| admin_finance | Read | Can view flag states |
| admin_support | Read | Can view flag states |
| admin_compliance | Read | Can view flag states |
| (public) | Read | Can check specific flag states via the public check endpoint |

## Business Rules

1. A feature flag's `enabled` field is the master kill-switch. If `enabled: false`, the flag evaluates as OFF for ALL users regardless of `rollout_percentage`. Only when `enabled: true` does the `rollout_percentage` take effect.
2. `rollout_percentage` ranges from 0 to 100. At 0%, the flag is effectively disabled for all users even if `enabled: true`. At 100%, the flag is enabled for all users. Between 1-99%, a deterministic hash (SHA-256 of `user_id + flag_name`) is used to determine inclusion consistently across sessions.
3. Flag names use `snake_case`, are unique platform-wide, and are immutable once created. Flag names cannot be updated (only the flag's state and rollout percentage can be changed). Deleting a flag is a permanent operation available only to `admin_super`.
4. In the production environment, only `admin_super` can modify flag states. In staging/development environments, any admin role can modify flags.
5. Every flag change (toggle on/off, rollout percentage change) is automatically captured in the audit log with `before_state` and `after_state` JSON snapshots.
6. The public flag check endpoint (`GET /feature-flags/check`) accepts a list of flag names and returns their evaluated state. This endpoint is unauthenticated and cached at the CDN/edge layer. No user-level personalisation is applied at this endpoint (it returns the base enabled/disabled state, not user-specific rollout evaluation).
7. The frontend SDK polls this endpoint every 60 seconds. The server sets `Cache-Control: public, max-age=60` on responses.
8. Flag state is cached in Redis with a 60-second TTL keyed on the environment. When a flag is updated, the Redis cache entry is immediately invalidated to propagate the change within the next poll cycle.

## API Endpoints

### 1. List All Feature Flags

```
GET /api/v1/admin/feature-flags
```

**Authentication:** Bearer JWT - any admin role
**Rate Limit:** 30 req/min per user

**Query Parameters:**
| Param | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| environment | string | No | production | production \| staging \| development |

**Success Response - `200 OK`:**
```json
{
  "success": true,
  "data": [
    {
      "name": "new_checkout_flow",
      "description": "Enables the redesigned 3-step checkout experience",
      "enabled": true,
      "environment": "production",
      "rollout_percentage": 50,
      "updated_by": {
        "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
        "name": "Ayesha Siddiqui"
      },
      "updated_at": "2026-07-20T10:00:00Z",
      "notes": "Gradual rollout to 50% - monitoring cart abandonment rate"
    },
    {
      "name": "cod_enabled",
      "description": "Cash on delivery payment option at checkout",
      "enabled": true,
      "environment": "production",
      "rollout_percentage": 100,
      "updated_by": {
        "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
        "name": "Ayesha Siddiqui"
      },
      "updated_at": "2025-06-01T09:00:00Z",
      "notes": null
    },
    {
      "name": "ai_rx_auto_fill",
      "description": "AI auto-fills medicine items from uploaded prescription image",
      "enabled": false,
      "environment": "production",
      "rollout_percentage": 0,
      "updated_by": {
        "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
        "name": "Ayesha Siddiqui"
      },
      "updated_at": "2026-07-24T01:00:00Z",
      "notes": "Disabled pending compliance review"
    }
  ],
  "meta": {}
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 401 | `UNAUTHORIZED` | Token missing or invalid |
| 403 | `FORBIDDEN` | Non-admin user |

---

### 2. Update Feature Flag

```
PATCH /api/v1/admin/feature-flags/:name
```

**Authentication:** Bearer JWT - `admin_super` (production); any admin (staging/dev)
**Rate Limit:** 20 req/min per user

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| :name | string | Flag name (snake_case) |

**Request Body (`application/json`):**
```json
{
  "enabled": "boolean - optional, kill-switch",
  "rollout_percentage": "integer - optional, 0-100",
  "notes": "string - optional, max:500, reason for the change"
}
```

**Success Response - `200 OK`:**
```json
{
  "success": true,
  "data": {
    "name": "new_checkout_flow",
    "enabled": true,
    "rollout_percentage": 100,
    "environment": "production",
    "updated_by": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "updated_at": "2026-07-24T02:00:00Z",
    "notes": "Rolled out to 100% - checkout A/B test complete"
  },
  "meta": {}
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `VALIDATION_ERROR` | `rollout_percentage` out of range or invalid boolean |
| 401 | `UNAUTHORIZED` | Token missing or invalid |
| 403 | `FORBIDDEN` | Non-super admin attempting to modify a production flag |
| 404 | `FLAG_NOT_FOUND` | No feature flag with the given name |

---

### 3. Feature Flag Summary

```
GET /api/v1/admin/feature-flags/summary
```

**Authentication:** Bearer JWT - any admin role
**Rate Limit:** 30 req/min per user

**Success Response - `200 OK`:**
```json
{
  "success": true,
  "data": {
    "total": 24,
    "enabled": 18,
    "disabled": 6,
    "partial_rollout": 3,
    "environments": {
      "production": { "total": 24, "enabled": 18 },
      "staging": { "total": 24, "enabled": 22 },
      "development": { "total": 24, "enabled": 24 }
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

### 4. Public Flag Check (Frontend SDK)

```
GET /api/v1/feature-flags/check
```

**Authentication:** None (public endpoint)
**Rate Limit:** 100 req/min per IP
**Cache:** `Cache-Control: public, max-age=60`

**Query Parameters:**
| Param | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| flags | string | Yes | - | Comma-separated list of flag names to check |
| environment | string | No | production | Target environment |

**Example Request:**
```
GET /api/v1/feature-flags/check?flags=cod_enabled,new_checkout_flow,ai_rx_auto_fill
```

**Success Response - `200 OK`:**
```json
{
  "success": true,
  "data": {
    "cod_enabled": true,
    "new_checkout_flow": true,
    "ai_rx_auto_fill": false
  },
  "meta": {
    "evaluated_at": "2026-07-24T02:00:00Z",
    "cache_max_age": 60
  }
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `VALIDATION_ERROR` | `flags` param missing or empty |

---

## Data Models

### FeatureFlag

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | UUID | PK, auto-gen | Primary key |
| name | VARCHAR(100) | NOT NULL, UNIQUE per environment | snake_case flag identifier |
| description | TEXT | NOT NULL | Human-readable description of what the flag controls |
| environment | VARCHAR(20) | NOT NULL | production \| staging \| development |
| enabled | BOOLEAN | NOT NULL, default false | Kill-switch: false overrides rollout_percentage |
| rollout_percentage | SMALLINT | NOT NULL, default 0, CHECK 0-100 | % of users to enable for when flag is ON |
| notes | TEXT | nullable | Admin notes about the most recent change |
| updated_by | UUID | FK ? admin_staff.id, nullable | Who last modified this flag |
| created_at | TIMESTAMPTZ | NOT NULL, default NOW() | Flag creation time |
| updated_at | TIMESTAMPTZ | NOT NULL | Last modification time |

## Acceptance Criteria

- [ ] Given a feature flag with `enabled: true` and `rollout_percentage: 50`, when `PATCH /admin/feature-flags/:name` is called with `{ "enabled": false }`, then the flag immediately evaluates as OFF for all users regardless of rollout_percentage, and the change is recorded in the audit log with `before_state` and `after_state`.
- [ ] Given a non-admin_super user in production, when `PATCH /admin/feature-flags/:name` is called, then `403 FORBIDDEN` is returned.
- [ ] Given `GET /api/v1/feature-flags/check?flags=cod_enabled,new_checkout_flow`, when called without authentication, then `200 OK` is returned with the boolean state of each requested flag and a `Cache-Control: public, max-age=60` header.
- [ ] Given a flag with `rollout_percentage: 150` in the request body, when `PATCH /admin/feature-flags/:name` is called, then `400 VALIDATION_ERROR` is returned.
- [ ] Given a flag update sets `rollout_percentage` to 100, then the Redis cache key for the flag's environment is immediately invalidated so the next poll returns the updated value.
- [ ] Given `GET /admin/feature-flags/summary`, then the response correctly counts `enabled`, `disabled`, and `partial_rollout` (flags where `enabled: true` and `rollout_percentage` is between 1 and 99).

## Dependencies

- EPIC-001 / STORY-003 - Admin auth (only admin_super can modify production flags)
- EPIC-021 / STORY-003 - Audit log middleware captures flag changes
- EPIC-000 / Infrastructure - Redis for flag state caching

## Notes

- Flags should be seeded via a migration file for initial platform flags. New flags should not be created via UI alone; they should be defined in code and deployed.
- The public check endpoint should NOT be used for user-specific rollout evaluation (that requires user context). User-level rollout evaluation happens server-side within each feature's business logic using `sha256(user_id + flag_name) % 100 < rollout_percentage`.
- Consider a flag creation endpoint in a future iteration, or handle flag creation via seed scripts only to prevent proliferation of undocumented flags.
