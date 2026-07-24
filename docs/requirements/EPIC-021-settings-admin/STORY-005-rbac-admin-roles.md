# STORY-005: Admin RBAC Role-Permission Matrix

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-005 |
| **Epic** | EPIC-021 - Settings & Platform Administration |
| **Priority** | P0 |
| **Complexity** | S |
| **Status** | Draft |

---

## Overview

This story exposes the read-only admin role-permission matrix as discoverable API endpoints so that the Admin HQ frontend can render accurate permission indicators and enforce UI-level access guards. The five built-in admin roles are fixed and non-customisable; their permission sets are defined in code and surfaced via this API. Each role's permissions follow the `resource:action` format and the API makes it easy to query what a specific role can do. No write operations exist in this story - any modification attempt is rejected. This story complements EPIC-001 STORY-005 (RBAC EPIC) by focusing on the admin-specific permission matrix as a governance and audit tool.

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| admin_super | Read | Can view full permission matrix for all roles |
| admin_operations | Read | Can view all admin roles for team awareness |
| admin_finance | Read | Can view all admin roles |
| admin_support | Read | Can view all admin roles |
| admin_compliance | Read | Can view all admin roles |

## Business Rules

1. The five admin roles (`admin_super`, `admin_operations`, `admin_finance`, `admin_support`, `admin_compliance`) are fixed and hardcoded in the application. They cannot be created, modified, or deleted via any API. Any attempt to write to these role definitions returns `405 METHOD_NOT_ALLOWED`.
2. `admin_super` holds an implicit wildcard permission (`*:*`). This is a special reserved permission that bypasses all resource-level checks in the RBAC middleware. It is returned in the API response as `["*:*"]` for transparency.
3. Permissions are expressed in the format `resource:action`. Valid actions include: `read`, `write`, `update`, `delete`, `cancel`, `approve`, `suspend`, `notify`, `export`, `audit`, `process`, `release-payout`, and `*` (wildcard for all actions on a resource).
4. A request from any admin role to an endpoint requiring a permission not in their role's permission set is rejected by the RBAC middleware with `403 FORBIDDEN` and error code `INSUFFICIENT_PERMISSIONS`. The error response includes the required permission for developer debugging.
5. The permission matrix is cached in application memory at startup and invalidated only on application restart. Since these are hardcoded permissions, there is no runtime cache invalidation mechanism.
6. The RBAC middleware must perform permission checks synchronously before the request handler executes. Permission checks must add no more than 2 ms to request latency (achieved via in-memory hash-map lookup).

## API Endpoints

### 1. List All Admin Roles with Permissions

```
GET /api/v1/admin/roles
```

**Authentication:** Bearer JWT - any admin role
**Rate Limit:** 30 req/min per user

**Success Response - `200 OK`:**
```json
{
  "success": true,
  "data": [
    {
      "role": "admin_super",
      "display_name": "Super Administrator",
      "description": "Full platform access. MFA required on every login.",
      "is_system": true,
      "is_customizable": false,
      "permissions": ["*:*"],
      "permission_count": null,
      "notes": "Implicit wildcard - grants all current and future permissions."
    },
    {
      "role": "admin_operations",
      "display_name": "Operations Manager",
      "description": "Manages day-to-day order fulfilment, pharmacy operations, and logistics.",
      "is_system": true,
      "is_customizable": false,
      "permissions": [
        "orders:read",
        "orders:write",
        "orders:cancel",
        "orders:assign-rider",
        "pharmacies:read",
        "pharmacies:update",
        "riders:read",
        "riders:write",
        "riders:assign",
        "riders:suspend",
        "logistics:read",
        "logistics:update",
        "catalogue:read"
      ],
      "permission_count": 13
    },
    {
      "role": "admin_finance",
      "display_name": "Finance Manager",
      "description": "Manages settlements, refunds, payouts, and financial analytics.",
      "is_system": true,
      "is_customizable": false,
      "permissions": [
        "finance:read",
        "finance:write",
        "finance:release-payout",
        "settlements:read",
        "settlements:process",
        "refunds:read",
        "refunds:approve",
        "refunds:reject",
        "taxes:read",
        "taxes:export",
        "analytics:finance",
        "customers:read",
        "wallet:credit"
      ],
      "permission_count": 13
    },
    {
      "role": "admin_support",
      "display_name": "Customer Support Agent",
      "description": "Handles tickets, disputes, and customer communication.",
      "is_system": true,
      "is_customizable": false,
      "permissions": [
        "tickets:read",
        "tickets:write",
        "tickets:close",
        "disputes:read",
        "disputes:write",
        "disputes:resolve",
        "customers:read",
        "customers:notify",
        "customers:flag",
        "orders:read"
      ],
      "permission_count": 10
    },
    {
      "role": "admin_compliance",
      "display_name": "Compliance Officer",
      "description": "Oversees prescription validation, catalogue compliance, and pharmacy KYC.",
      "is_system": true,
      "is_customizable": false,
      "permissions": [
        "prescriptions:read",
        "prescriptions:review",
        "prescriptions:approve",
        "prescriptions:reject",
        "compliance:read",
        "compliance:audit",
        "compliance:flag",
        "catalogue:read",
        "catalogue:update",
        "pharmacies:read",
        "kyc:read",
        "kyc:approve",
        "kyc:reject"
      ],
      "permission_count": 13
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

### 2. Get Permissions for a Specific Role

```
GET /api/v1/admin/roles/:role/permissions
```

**Authentication:** Bearer JWT - any admin role
**Rate Limit:** 30 req/min per user

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| :role | string | Admin role slug: admin_super \| admin_operations \| admin_finance \| admin_support \| admin_compliance |

**Success Response - `200 OK`:**
```json
{
  "success": true,
  "data": {
    "role": "admin_finance",
    "display_name": "Finance Manager",
    "permissions": [
      {
        "permission": "finance:read",
        "resource": "finance",
        "action": "read",
        "description": "View financial reports, summaries, and P&L data"
      },
      {
        "permission": "finance:write",
        "resource": "finance",
        "action": "write",
        "description": "Create and modify financial adjustments"
      },
      {
        "permission": "finance:release-payout",
        "resource": "finance",
        "action": "release-payout",
        "description": "Trigger pharmacy payout releases to bank accounts"
      },
      {
        "permission": "settlements:read",
        "resource": "settlements",
        "action": "read",
        "description": "View settlement records and breakdowns"
      },
      {
        "permission": "settlements:process",
        "resource": "settlements",
        "action": "process",
        "description": "Process pending pharmacy settlements"
      },
      {
        "permission": "refunds:read",
        "resource": "refunds",
        "action": "read",
        "description": "View refund requests and history"
      },
      {
        "permission": "refunds:approve",
        "resource": "refunds",
        "action": "approve",
        "description": "Approve pending customer refund requests"
      },
      {
        "permission": "refunds:reject",
        "resource": "refunds",
        "action": "reject",
        "description": "Reject refund requests with reason"
      },
      {
        "permission": "taxes:read",
        "resource": "taxes",
        "action": "read",
        "description": "View GST and tax reports"
      },
      {
        "permission": "taxes:export",
        "resource": "taxes",
        "action": "export",
        "description": "Export tax reports as CSV/PDF for filing"
      },
      {
        "permission": "analytics:finance",
        "resource": "analytics",
        "action": "finance",
        "description": "Access finance-specific analytics dashboards and revenue reports"
      },
      {
        "permission": "customers:read",
        "resource": "customers",
        "action": "read",
        "description": "View customer profiles for financial investigation"
      },
      {
        "permission": "wallet:credit",
        "resource": "wallet",
        "action": "credit",
        "description": "Manually credit customer wallets for refunds or goodwill"
      }
    ],
    "permission_count": 13
  },
  "meta": {}
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 401 | `UNAUTHORIZED` | Token missing or invalid |
| 403 | `FORBIDDEN` | Non-admin user |
| 404 | `ROLE_NOT_FOUND` | Role slug not recognised |
| 405 | `METHOD_NOT_ALLOWED` | Any write method (POST, PUT, PATCH, DELETE) attempted on this endpoint |

---

## Data Models

This story does not introduce database models. The admin role permission matrix is defined as a hardcoded in-memory map in the application's RBAC service. The structure is described below for documentation purposes.

### AdminRolePermissionMatrix (In-Memory Definition)

| Role | Permissions (resource:action) |
|------|-------------------------------|
| admin_super | `*:*` (wildcard - all permissions) |
| admin_operations | `orders:read`, `orders:write`, `orders:cancel`, `orders:assign-rider`, `pharmacies:read`, `pharmacies:update`, `riders:read`, `riders:write`, `riders:assign`, `riders:suspend`, `logistics:read`, `logistics:update`, `catalogue:read` |
| admin_finance | `finance:read`, `finance:write`, `finance:release-payout`, `settlements:read`, `settlements:process`, `refunds:read`, `refunds:approve`, `refunds:reject`, `taxes:read`, `taxes:export`, `analytics:finance`, `customers:read`, `wallet:credit` |
| admin_support | `tickets:read`, `tickets:write`, `tickets:close`, `disputes:read`, `disputes:write`, `disputes:resolve`, `customers:read`, `customers:notify`, `customers:flag`, `orders:read` |
| admin_compliance | `prescriptions:read`, `prescriptions:review`, `prescriptions:approve`, `prescriptions:reject`, `compliance:read`, `compliance:audit`, `compliance:flag`, `catalogue:read`, `catalogue:update`, `pharmacies:read`, `kyc:read`, `kyc:approve`, `kyc:reject` |

### Permission (Reference Definition)

| Field | Type | Description |
|-------|------|-------------|
| permission | VARCHAR(60) | Composite `resource:action` string |
| resource | VARCHAR(30) | The resource being controlled (orders, finance, etc.) |
| action | VARCHAR(30) | The operation (read, write, cancel, approve, etc.) |
| description | TEXT | Human-readable description |
| domain | VARCHAR(10) | admin (all permissions in this matrix) |

## Acceptance Criteria

- [ ] Given an authenticated `admin_support` user, when `GET /admin/roles` is called, then all 5 admin roles are returned with their permission lists, and `admin_support`'s permissions include `customers:flag` and `orders:read` but NOT `finance:release-payout`.
- [ ] Given `GET /admin/roles/admin_super/permissions`, then the response returns `permissions` containing exactly `[{ "permission": "*:*", ... }]` and a note explaining the wildcard.
- [ ] Given `GET /admin/roles/admin_finance/permissions`, then the response returns exactly 13 permission objects including `finance:release-payout` and `wallet:credit`.
- [ ] Given any admin role user, when a `POST /admin/roles/admin_operations/permissions` request is attempted, then `405 METHOD_NOT_ALLOWED` is returned with a message indicating admin roles are not customisable.
- [ ] Given `GET /admin/roles/admin_billing` (non-existent role), when the endpoint is called, then `404 ROLE_NOT_FOUND` is returned.
- [ ] Given an API request with `admin_support` token that targets an endpoint requiring `finance:release-payout`, when the middleware runs, then `403 FORBIDDEN` is returned with error body containing `{ "code": "INSUFFICIENT_PERMISSIONS", "required_permission": "finance:release-payout" }`.
- [ ] Given the RBAC middleware performs a permission check, when measured in isolation, the check adds ? 2 ms to request latency (validated via performance test).

## Dependencies

- EPIC-001 / STORY-003 - Admin authentication embeds `role` in JWT payload consumed by RBAC middleware
- EPIC-001 / STORY-005 - This story focuses on admin roles; pharmacy roles are covered in EPIC-001 STORY-005
- EPIC-021 / STORY-001 - Admin staff management assigns roles from this fixed set

## Notes

- This story is intentionally read-only. The permission matrix is an operational artefact reflecting security policy, and changes to it require a code review and deployment - not an ad-hoc API call.
- The RBAC middleware should be implemented as an Express/Fastify middleware function that is applied globally and consults an in-memory `Map<role, Set<permission>>` loaded at startup. For `admin_super`, the check short-circuits to `true` before any resource lookup.
- Document the full permission matrix in a `PERMISSIONS.md` file alongside the codebase for developer onboarding.
- Future consideration: add a `GET /admin/roles/effective-permissions?roles=admin_operations,admin_finance` endpoint to compute the union of multiple roles' permissions for multi-role staff scenarios.
