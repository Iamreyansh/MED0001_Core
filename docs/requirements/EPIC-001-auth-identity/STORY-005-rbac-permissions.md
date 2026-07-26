# STORY-005: Role-Based Access Control (RBAC)

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-005 |
| **Epic** | EPIC-001 - Authentication & Identity |
| **Priority** | P0 |
| **Complexity** | L |
| **Status** | Draft |

---

## Overview

This story establishes the RBAC system for both the Admin HQ and the Pharmacy Dashboard. On the admin side, five fixed built-in roles (admin_super, admin_operations, admin_finance, admin_support, admin_compliance) have non-modifiable permission sets that gate access to every admin API. On the pharmacy side, five built-in roles exist (owner, manager, pharmacist, cashier, delivery) and pharmacy owners can additionally define custom roles scoped to their specific pharmacy. Permissions follow a `resource:action` format (e.g., `orders:cancel`, `inventory:write`). All permission checks happen server-side via middleware on every request, with 403 returned immediately on violation. This story exposes the read APIs for exploring roles and the write APIs for managing pharmacy custom roles.

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| admin_super | Admin | Can read admin roles; cannot modify fixed roles |
| admin_operations | Read | Can read admin role/permission lists |
| admin_finance | Read | Can read admin role/permission lists |
| admin_support | Read | Can read admin role/permission lists |
| admin_compliance | Read | Can read admin role/permission lists |
| pharmacy_owner | Admin | Can create, read, update custom roles for their own pharmacy |
| pharmacy_staff (manager) | Write | Can update custom role permissions if granted `staff:manage` permission |

## Business Rules

1. All permission checks are performed server-side on every API request via an RBAC middleware layer. Client-side permission hints are informational only and never trusted.
2. Admin roles are system-defined and immutable: `admin_super`, `admin_operations`, `admin_finance`, `admin_support`, `admin_compliance`. No admin staff member can create, modify, or delete these roles. Attempting to do so returns `403 FORBIDDEN`.
3. `admin_super` has an implicit wildcard permission (`*:*`) that bypasses all resource-level checks. This is not stored in a permissions table but hardcoded in the RBAC middleware.
4. Pharmacy built-in roles (`owner`, `manager`, `pharmacist`, `cashier`, `delivery`) are system-defined and immutable. Custom roles are created by pharmacy owners and are scoped exclusively to the pharmacy that created them.
5. A pharmacy custom role inherits no permissions by default. Permissions are assigned explicitly via `PUT /pharmacy/roles/:id/permissions`. Custom roles cannot be assigned permissions outside the pharmacy domain (e.g., they cannot be granted admin permissions).
6. `pharmacy_owner` always retains all pharmacy permissions regardless of the role record. The middleware grants all pharmacy-domain permissions to any user with role `pharmacy_owner` for their active pharmacy.
7. Permissions are stored and checked in the format `resource:action`, where `action` can be a specific verb (`read`, `write`, `cancel`, `approve`, `export`) or a wildcard (`*`). A permission `orders:*` grants all actions on the `orders` resource.
8. If a role has `orders:*`, it implicitly covers `orders:read`, `orders:write`, `orders:cancel`, etc. The RBAC middleware expands wildcards before checking.
9. Custom pharmacy roles are soft-deleted; they cannot be hard-deleted if any staff member is currently assigned to them. The pharmacy owner must re-assign affected staff before deleting the role.

## API Endpoints

### 1. List Admin Roles

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
      "description": "Full platform access with no restrictions. Required for MFA.",
      "is_system": true,
      "permissions": ["*:*"]
    },
    {
      "role": "admin_operations",
      "display_name": "Operations Manager",
      "description": "Manages orders, logistics, pharmacies, and riders.",
      "is_system": true,
      "permissions": [
        "orders:*",
        "pharmacies:read",
        "pharmacies:update",
        "riders:*",
        "logistics:*",
        "catalogue:read"
      ]
    },
    {
      "role": "admin_finance",
      "display_name": "Finance Manager",
      "description": "Manages settlements, refunds, payouts, and financial analytics.",
      "is_system": true,
      "permissions": [
        "finance:*",
        "settlements:*",
        "refunds:*",
        "taxes:*",
        "analytics:finance"
      ]
    },
    {
      "role": "admin_support",
      "display_name": "Customer Support",
      "description": "Handles tickets, disputes, and customer-facing issues.",
      "is_system": true,
      "permissions": [
        "tickets:*",
        "disputes:*",
        "customers:read",
        "customers:notify",
        "orders:read"
      ]
    },
    {
      "role": "admin_compliance",
      "display_name": "Compliance Officer",
      "description": "Oversees prescription validation, catalogue compliance, and pharmacy KYC.",
      "is_system": true,
      "permissions": [
        "prescriptions:*",
        "compliance:*",
        "catalogue:update",
        "pharmacies:read"
      ]
    }
  ],
  "meta": {}
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 401 | `UNAUTHORIZED` | Token missing or invalid |
| 403 | `FORBIDDEN` | Non-admin user attempting access |

---

### 2. List All Admin Permissions

```
GET /api/v1/admin/permissions
```

**Authentication:** Bearer JWT - any admin role
**Rate Limit:** 30 req/min per user

**Query Parameters:**
| Param | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| resource | string | No | - | Filter by resource name (e.g., `orders`) |

**Success Response - `200 OK`:**
```json
{
  "success": true,
  "data": [
    { "resource": "orders", "action": "read", "permission": "orders:read", "description": "View order details and history" },
    { "resource": "orders", "action": "write", "permission": "orders:write", "description": "Create and modify orders" },
    { "resource": "orders", "action": "cancel", "permission": "orders:cancel", "description": "Cancel any order" },
    { "resource": "pharmacies", "action": "read", "permission": "pharmacies:read", "description": "View pharmacy profiles and details" },
    { "resource": "pharmacies", "action": "update", "permission": "pharmacies:update", "description": "Update pharmacy information" },
    { "resource": "pharmacies", "action": "suspend", "permission": "pharmacies:suspend", "description": "Suspend or reactivate a pharmacy" },
    { "resource": "riders", "action": "read", "permission": "riders:read", "description": "View rider profiles and status" },
    { "resource": "riders", "action": "assign", "permission": "riders:assign", "description": "Manually assign riders to orders" },
    { "resource": "finance", "action": "read", "permission": "finance:read", "description": "View financial reports and summaries" },
    { "resource": "finance", "action": "release-payout", "permission": "finance:release-payout", "description": "Trigger pharmacy payout releases" },
    { "resource": "refunds", "action": "approve", "permission": "refunds:approve", "description": "Approve refund requests" },
    { "resource": "customers", "action": "read", "permission": "customers:read", "description": "View customer profiles and orders" },
    { "resource": "customers", "action": "notify", "permission": "customers:notify", "description": "Send notifications to customers" },
    { "resource": "tickets", "action": "read", "permission": "tickets:read", "description": "View support tickets" },
    { "resource": "tickets", "action": "write", "permission": "tickets:write", "description": "Create, update, and close tickets" },
    { "resource": "prescriptions", "action": "review", "permission": "prescriptions:review", "description": "Review and approve/reject prescriptions" },
    { "resource": "compliance", "action": "audit", "permission": "compliance:audit", "description": "Run compliance audits on pharmacies" },
    { "resource": "catalogue", "action": "read", "permission": "catalogue:read", "description": "Browse the medicine catalogue" },
    { "resource": "catalogue", "action": "update", "permission": "catalogue:update", "description": "Update medicine information and categories" },
    { "resource": "analytics", "action": "finance", "permission": "analytics:finance", "description": "Access finance-specific analytics dashboards" },
    { "resource": "settlements", "action": "read", "permission": "settlements:read", "description": "View settlement records" },
    { "resource": "settlements", "action": "process", "permission": "settlements:process", "description": "Process pending settlements" },
    { "resource": "taxes", "action": "read", "permission": "taxes:read", "description": "View tax reports and filings" },
    { "resource": "logistics", "action": "read", "permission": "logistics:read", "description": "View delivery and logistics data" }
  ],
  "meta": {}
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 401 | `UNAUTHORIZED` | Token missing or invalid |
| 403 | `FORBIDDEN` | Non-admin user attempting access |

---

### 3. List Pharmacy Roles

```
GET /api/v1/pharmacy/roles
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 30 req/min per user

**Success Response - `200 OK`:**
```json
{
  "success": true,
  "data": [
    {
      "id": "system-owner",
      "name": "owner",
      "display_name": "Pharmacy Owner",
      "is_system": true,
      "pharmacy_id": null,
      "permissions": ["*"],
      "staff_count": 1
    },
    {
      "id": "system-manager",
      "name": "manager",
      "display_name": "Manager",
      "is_system": true,
      "pharmacy_id": null,
      "permissions": ["orders:*", "inventory:*", "staff:read", "reports:read"],
      "staff_count": 2
    },
    {
      "id": "system-pharmacist",
      "name": "pharmacist",
      "display_name": "Pharmacist",
      "is_system": true,
      "pharmacy_id": null,
      "permissions": ["orders:fulfill", "inventory:read", "prescriptions:verify"],
      "staff_count": 3
    },
    {
      "id": "system-cashier",
      "name": "cashier",
      "display_name": "Cashier",
      "is_system": true,
      "pharmacy_id": null,
      "permissions": ["orders:read", "orders:pos-create", "payments:collect"],
      "staff_count": 1
    },
    {
      "id": "system-delivery",
      "name": "delivery",
      "display_name": "Delivery Staff",
      "is_system": true,
      "pharmacy_id": null,
      "permissions": ["orders:read", "orders:dispatch"],
      "staff_count": 2
    },
    {
      "id": "c1d2e3f4-a5b6-7890-cdef-012345678901",
      "name": "senior_pharmacist",
      "display_name": "Senior Pharmacist",
      "is_system": false,
      "pharmacy_id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "permissions": ["orders:fulfill", "inventory:*", "prescriptions:verify", "reports:read"],
      "staff_count": 1
    }
  ],
  "meta": {}
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 401 | `UNAUTHORIZED` | Token missing or invalid |
| 403 | `FORBIDDEN` | User not a pharmacy owner or staff |

---

### 4. Create Custom Pharmacy Role

```
POST /api/v1/pharmacy/roles
```

**Authentication:** Bearer JWT - `pharmacy_owner`
**Rate Limit:** 20 req/min per user

**Request Body (`application/json`):**
```json
{
  "name": "string - required, snake_case, max:50, must be unique within this pharmacy",
  "display_name": "string - required, max:100, human-readable label",
  "permissions": ["string - array of permission strings e.g. orders:read, inventory:write"]
}
```

**Success Response - `201 Created`:**
```json
{
  "success": true,
  "data": {
    "id": "c1d2e3f4-a5b6-7890-cdef-012345678901",
    "name": "senior_pharmacist",
    "display_name": "Senior Pharmacist",
    "is_system": false,
    "pharmacy_id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "permissions": ["orders:fulfill", "inventory:*", "prescriptions:verify", "reports:read"],
    "created_at": "2026-07-24T02:00:00Z"
  },
  "meta": {}
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `VALIDATION_ERROR` | Missing name, invalid format, or unknown permission string |
| 401 | `UNAUTHORIZED` | Token missing or invalid |
| 403 | `FORBIDDEN` | Not a pharmacy_owner for the active pharmacy |
| 409 | `ROLE_NAME_CONFLICT` | Role with this name already exists in this pharmacy |

---

### 5. Get Role Permissions

```
GET /api/v1/pharmacy/roles/:id/permissions
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 30 req/min per user

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| :id | UUID or string | Role ID (UUID for custom roles; system name slug for built-in roles) |

**Success Response - `200 OK`:**
```json
{
  "success": true,
  "data": {
    "role_id": "c1d2e3f4-a5b6-7890-cdef-012345678901",
    "role_name": "senior_pharmacist",
    "is_system": false,
    "permissions": [
      { "permission": "orders:fulfill", "resource": "orders", "action": "fulfill" },
      { "permission": "inventory:*", "resource": "inventory", "action": "*" },
      { "permission": "prescriptions:verify", "resource": "prescriptions", "action": "verify" },
      { "permission": "reports:read", "resource": "reports", "action": "read" }
    ]
  },
  "meta": {}
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 401 | `UNAUTHORIZED` | Token missing or invalid |
| 403 | `FORBIDDEN` | Attempting to view another pharmacy's custom role |
| 404 | `ROLE_NOT_FOUND` | Role ID not found |

---

### 6. Update Role Permissions (Bulk Set)

```
PUT /api/v1/pharmacy/roles/:id/permissions
```

**Authentication:** Bearer JWT - `pharmacy_owner`, or `pharmacy_staff` with `staff:manage`
**Rate Limit:** 20 req/min per user

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| :id | UUID | ID of the custom pharmacy role to update |

**Request Body (`application/json`):**
```json
{
  "permissions": [
    "orders:fulfill",
    "orders:read",
    "inventory:*",
    "prescriptions:verify",
    "reports:read"
  ]
}
```

**Success Response - `200 OK`:**
```json
{
  "success": true,
  "data": {
    "role_id": "c1d2e3f4-a5b6-7890-cdef-012345678901",
    "role_name": "senior_pharmacist",
    "permissions": [
      "orders:fulfill",
      "orders:read",
      "inventory:*",
      "prescriptions:verify",
      "reports:read"
    ],
    "updated_at": "2026-07-24T03:00:00Z"
  },
  "meta": {}
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `VALIDATION_ERROR` | Unknown permission string in the array |
| 401 | `UNAUTHORIZED` | Token missing or invalid |
| 403 | `FORBIDDEN` | Not the owner (or staff without `staff:manage`) of the pharmacy that owns this role; or attempting to modify a system role |
| 404 | `ROLE_NOT_FOUND` | Role ID not found or is a system role (immutable) |

---

### 7. Soft-Delete Custom Pharmacy Role

```
DELETE /api/v1/pharmacy/roles/:id
```

**Authentication:** Bearer JWT - `pharmacy_owner`
**Rate Limit:** 20 req/min per user

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| :id | UUID | ID of the custom pharmacy role to soft-delete |

**Success Response - `204 No Content`:** empty body

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 401 | `UNAUTHORIZED` | Token missing or invalid |
| 403 | `FORBIDDEN` | Not the pharmacy owner; or attempting to delete a system role; or role belongs to another pharmacy |
| 404 | `ROLE_NOT_FOUND` | Role ID not found |
| 409 | `ROLE_IN_USE` | At least one staff member is still assigned to this role |

---

## Data Models

### PharmacyRole

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | UUID | PK, auto-gen | Primary key |
| pharmacy_id | UUID | FK ? pharmacies.id, nullable | NULL for system roles; set for custom roles |
| name | VARCHAR(50) | NOT NULL | snake_case identifier |
| display_name | VARCHAR(100) | NOT NULL | Human-readable label |
| is_system | BOOLEAN | NOT NULL, default false | True for built-in platform roles |
| permissions | TEXT[] | NOT NULL, default '{}' | Array of `resource:action` strings |
| created_by | UUID | FK ? pharmacy_staff.id, nullable | Who created this custom role |
| created_at | TIMESTAMPTZ | NOT NULL, default NOW() | Creation timestamp |
| updated_at | TIMESTAMPTZ | NOT NULL | Last update timestamp |
| deleted_at | TIMESTAMPTZ | nullable | Soft delete; NULL = active |

### Permission (reference table - admin domain)

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| resource | VARCHAR(50) | PK (composite) | Resource name (e.g., `orders`, `finance`) |
| action | VARCHAR(50) | PK (composite) | Action verb (e.g., `read`, `cancel`, `*`) |
| description | TEXT | NOT NULL | Human-readable description of the permission |
| domain | VARCHAR(20) | NOT NULL | admin \| pharmacy |

## Acceptance Criteria

- [ ] Given an authenticated `admin_super`, when `GET /admin/roles` is called, then all 5 admin roles are returned with their correct permission sets, including `admin_super` showing `permissions: ["*:*"]`.
- [ ] Given an authenticated `pharmacy_owner`, when `POST /pharmacy/roles` is called with a valid `name` and `permissions` array, then a new custom role is created scoped to the owner's active pharmacy and returned with a UUID.
- [ ] Given a custom pharmacy role, when `PUT /pharmacy/roles/:id/permissions` is called with a new permissions array, then the permissions are fully replaced (not merged), and the `updated_at` timestamp is refreshed.
- [ ] Given an authenticated `pharmacy_staff` (non-owner), when `POST /pharmacy/roles` is called, then `403 FORBIDDEN` is returned.
- [ ] Given an API request with a token whose role is `admin_support`, when the request targets an endpoint requiring `pharmacies:suspend`, then `403 FORBIDDEN` is returned with error code `FORBIDDEN`.
- [ ] Given a custom pharmacy role that is assigned to at least one staff member, when a `DELETE` is attempted (soft-delete), then the delete is rejected with `409 ROLE_IN_USE` until all assigned staff are reassigned.
- [ ] Given `GET /admin/permissions`, when called with `resource=orders` query param, then only permissions for the `orders` resource are returned.

## Dependencies

- EPIC-001 / STORY-002 - Pharmacy staff assignments reference role IDs
- EPIC-001 / STORY-003 - Admin auth uses admin roles from this story's fixed role matrix
- EPIC-010 / STORY-001 - Pharmacy onboarding creates the initial owner assignment using the system `owner` role

## Notes

- The RBAC middleware should load role permissions from Redis on application startup and invalidate the cache whenever role permissions are updated via the API.
- Wildcard expansion (`orders:*` ? all order permissions) should happen at middleware load time, not at request time, to minimise per-request overhead.
- A `pharmacy_owner` always has all pharmacy permissions regardless of their assigned role record - this is a hard-coded rule in the middleware, not derived from the permission table.
- Consider adding a permission inheritance / parent-role concept in a future iteration for large pharmacy chains with tiered management hierarchies.
