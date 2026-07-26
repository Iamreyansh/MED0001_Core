# STORY-001: Admin Staff Management

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-001 |
| **Epic** | EPIC-021 - Settings & Platform Administration |
| **Priority** | P0 |
| **Complexity** | M |
| **Status** | Draft |

---

## Overview

This story provides the full lifecycle management of the Namma MedMate admin team, exclusively controlled by `admin_super`. Admin staff are invited via email (not self-registered), assigned one of the five fixed roles, and can be suspended or removed. Security guardrails prevent the last `admin_super` from being removed and prevent any admin from removing themselves. All staff management actions are automatically captured in the platform audit log. The story also covers forced password reset email dispatch for credential recovery.

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| admin_super | Admin | Full CRUD over all admin staff; only role that can create/modify/delete |
| admin_operations | Read | Can view the admin staff list for team awareness |
| admin_finance | Read | Can view the admin staff list |
| admin_support | Read | Can view the admin staff list |
| admin_compliance | Read | Can view the admin staff list |

## Business Rules

1. Only `admin_super` can create (invite), modify roles, suspend, or delete admin staff members. Any other admin role attempting these write operations receives `403 FORBIDDEN`.
2. Admin staff are created via an invitation flow: the `POST /admin/staff` endpoint creates a staff record with `status: INVITED` and dispatches an invitation email containing a time-limited signup link (expires in 48 hours). The invited staff member sets their password via the link.
3. The `admin_super` role cannot be self-assigned or modified by a non-super. Only an existing `admin_super` can elevate another admin to `admin_super`. An `admin_super` cannot change their own role to a lower role.
4. There must always be at least 1 admin staff member with `status: ACTIVE` and `role: admin_super` on the platform. Any operation that would violate this constraint (remove, suspend, or role-change the last active admin_super) is rejected with `422 LAST_SUPER_ADMIN`.
5. A removed (deleted) admin staff member immediately has all their active sessions revoked (cascading logout). The staff record is soft-deleted (`deleted_at` set) and is no longer visible in the staff list by default.
6. Suspended (`status: SUSPENDED`) staff members retain their record and can be re-activated by `admin_super` via a role/status update. Suspended staff lose all active sessions at the moment of suspension.
7. An `admin_super` cannot delete or suspend themselves. Attempting to do so returns `422 CANNOT_MODIFY_SELF`.
8. Password reset emails sent via `POST /admin/staff/:id/reset-password` use a one-time-use reset token embedded in a link (TTL 4 hours). The password reset itself is handled via a separate public endpoint (not in this story's scope - deferred to auth flows).
9. The invite email link TTL is exactly 48 hours from `invited_at`. If the link expires without the invited staff completing sign-up, `status` remains `INVITED` and admin_super can re-send the invite by calling the same endpoint again or a future resend endpoint.

## API Endpoints

### 1. List Admin Staff

```
GET /api/v1/admin/staff
```

**Authentication:** Bearer JWT - any admin role
**Rate Limit:** 30 req/min per user

**Query Parameters:**
| Param | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| page | integer | No | 1 | Page number |
| limit | integer | No | 20 | Results per page, max 100 |
| role | string | No | - | Filter by role: admin_super \| admin_operations \| admin_finance \| admin_support \| admin_compliance |
| status | string | No | - | Filter by status: ACTIVE \| SUSPENDED \| INVITED |
| search | string | No | - | Search by name or email |

**Success Response - `200 OK`:**
```json
{
  "success": true,
  "data": [
    {
      "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
      "name": "Ayesha Siddiqui",
      "email": "ayesha@nammamedmate.com",
      "role": "admin_super",
      "status": "ACTIVE",
      "mfa_enabled": true,
      "last_active_at": "2026-07-24T01:00:00Z",
      "created_at": "2025-06-01T09:00:00Z"
    },
    {
      "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "name": "Sundar Rajan",
      "email": "sundar@nammamedmate.com",
      "role": "admin_operations",
      "status": "ACTIVE",
      "mfa_enabled": false,
      "last_active_at": "2026-07-23T18:00:00Z",
      "created_at": "2025-07-10T11:00:00Z"
    }
  ],
  "meta": {
    "page": 1,
    "limit": 20,
    "total": 8,
    "has_next": false
  }
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 401 | `UNAUTHORIZED` | Token missing or invalid |
| 403 | `FORBIDDEN` | Non-admin user |

---

### 2. Invite Admin Staff Member

```
POST /api/v1/admin/staff
```

**Authentication:** Bearer JWT - `admin_super`
**Rate Limit:** 10 req/min per user

**Request Body (`application/json`):**
```json
{
  "name": "string - required, max:100",
  "email": "string - required, valid email address, must be unique platform-wide",
  "role": "string - required, enum: admin_operations|admin_finance|admin_support|admin_compliance (admin_super not directly assignable here - requires separate elevation)",
  "send_invite_email": "boolean - required, must be true (always send invite)"
}
```

**Success Response - `201 Created`:**
```json
{
  "success": true,
  "data": {
    "id": "c1d2e3f4-a5b6-7890-cdef-012345678901",
    "name": "Meera Krishnan",
    "email": "meera@nammamedmate.com",
    "role": "admin_support",
    "status": "INVITED",
    "mfa_enabled": false,
    "invited_by": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "invite_expires_at": "2026-07-26T02:00:00Z",
    "created_at": "2026-07-24T02:00:00Z"
  },
  "meta": {}
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `VALIDATION_ERROR` | Missing fields, invalid email, or invalid role enum |
| 401 | `UNAUTHORIZED` | Token missing or invalid |
| 403 | `FORBIDDEN` | Caller is not admin_super |
| 409 | `EMAIL_ALREADY_EXISTS` | Admin account with this email already exists |

---

### 3. Get Admin Staff Detail

```
GET /api/v1/admin/staff/:id
```

**Authentication:** Bearer JWT - any admin role
**Rate Limit:** 30 req/min per user

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| :id | UUID | Admin staff member ID |

**Success Response - `200 OK`:**
```json
{
  "success": true,
  "data": {
    "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "name": "Sundar Rajan",
    "email": "sundar@nammamedmate.com",
    "role": "admin_operations",
    "status": "ACTIVE",
    "mfa_enabled": false,
    "last_active_at": "2026-07-23T18:00:00Z",
    "invited_by": {
      "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
      "name": "Ayesha Siddiqui"
    },
    "created_at": "2025-07-10T11:00:00Z",
    "audit_trail": [
      {
        "action": "staff.role_changed",
        "from": "admin_support",
        "to": "admin_operations",
        "by": "Ayesha Siddiqui",
        "at": "2026-02-01T10:00:00Z"
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
| 404 | `STAFF_NOT_FOUND` | No staff with given ID |

---

### 4. Update Admin Staff

```
PATCH /api/v1/admin/staff/:id
```

**Authentication:** Bearer JWT - `admin_super`
**Rate Limit:** 10 req/min per user

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| :id | UUID | Admin staff member ID |

**Request Body (`application/json`):**
```json
{
  "name": "string - optional, max:100",
  "role": "string - optional, enum: admin_super|admin_operations|admin_finance|admin_support|admin_compliance",
  "status": "string - optional, enum: ACTIVE|SUSPENDED"
}
```

**Success Response - `200 OK`:**
```json
{
  "success": true,
  "data": {
    "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "name": "Sundar Rajan",
    "role": "admin_operations",
    "status": "SUSPENDED",
    "updated_at": "2026-07-24T02:00:00Z"
  },
  "meta": {}
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `VALIDATION_ERROR` | Invalid field values |
| 401 | `UNAUTHORIZED` | Token missing or invalid |
| 403 | `FORBIDDEN` | Caller is not admin_super |
| 404 | `STAFF_NOT_FOUND` | Staff member not found |
| 422 | `LAST_SUPER_ADMIN` | Operation would remove the last active admin_super |
| 422 | `CANNOT_MODIFY_SELF` | Admin_super attempting to modify their own role/status |

---

### 5. Remove Admin Staff

```
DELETE /api/v1/admin/staff/:id
```

**Authentication:** Bearer JWT - `admin_super`
**Rate Limit:** 5 req/min per user

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| :id | UUID | Admin staff member ID to remove |

**Success Response - `200 OK`:**
```json
{
  "success": true,
  "data": {
    "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "message": "Admin staff member removed. All active sessions have been revoked."
  },
  "meta": {}
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 401 | `UNAUTHORIZED` | Token missing or invalid |
| 403 | `FORBIDDEN` | Caller is not admin_super |
| 404 | `STAFF_NOT_FOUND` | Staff member not found |
| 422 | `LAST_SUPER_ADMIN` | Cannot remove the last active admin_super |
| 422 | `CANNOT_MODIFY_SELF` | Admin_super trying to remove themselves |

---

### 6. Send Password Reset Email

```
POST /api/v1/admin/staff/:id/reset-password
```

**Authentication:** Bearer JWT - `admin_super`
**Rate Limit:** 5 req/hour per target staff member

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| :id | UUID | Admin staff member ID |

**Success Response - `200 OK`:**
```json
{
  "success": true,
  "data": {
    "message": "Password reset email sent to sundar@nammamedmate.com.",
    "reset_link_expires_at": "2026-07-24T06:00:00Z"
  },
  "meta": {}
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 401 | `UNAUTHORIZED` | Token missing or invalid |
| 403 | `FORBIDDEN` | Caller is not admin_super |
| 404 | `STAFF_NOT_FOUND` | Staff member not found |
| 429 | `RATE_LIMITED` | Too many reset emails sent for this staff member |

---

## Data Models

### AdminStaff

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | UUID | PK, auto-gen | Primary key |
| name | VARCHAR(100) | NOT NULL | Full display name |
| email | VARCHAR(255) | UNIQUE, NOT NULL | Login email; unique platform-wide |
| password_hash | VARCHAR(60) | nullable | bcrypt hash; NULL until invite completed |
| role | VARCHAR(30) | NOT NULL | admin_super \| admin_operations \| admin_finance \| admin_support \| admin_compliance |
| status | VARCHAR(20) | NOT NULL, default 'INVITED' | ACTIVE \| SUSPENDED \| INVITED |
| mfa_enabled | BOOLEAN | NOT NULL, default false | Whether TOTP is enrolled |
| totp_secret | VARCHAR(32) | nullable, encrypted | AES-256-GCM encrypted TOTP secret |
| backup_codes | JSONB | nullable | Hashed backup codes array |
| last_active_at | TIMESTAMPTZ | nullable | Most recent API request timestamp |
| invited_by | UUID | FK ? admin_staff.id, nullable | Who sent the invite |
| invite_expires_at | TIMESTAMPTZ | nullable | Invite link expiry time |
| deleted_at | TIMESTAMPTZ | nullable | Soft delete |
| created_at | TIMESTAMPTZ | NOT NULL, default NOW() | Account creation timestamp |
| updated_at | TIMESTAMPTZ | NOT NULL | Last update timestamp |

## Acceptance Criteria

- [ ] Given an authenticated `admin_super`, when `POST /admin/staff` is called with a valid email, name, and role, then a new AdminStaff record with `status: INVITED` is created and an invitation email is dispatched; the response includes `invite_expires_at` set to 48 hours in the future.
- [ ] Given an admin_operations user, when `POST /admin/staff` is called, then `403 FORBIDDEN` is returned - only admin_super can create staff.
- [ ] Given there is only 1 active admin_super, when `DELETE /admin/staff/:id` is called for that admin_super, then `422 LAST_SUPER_ADMIN` is returned and the record is not deleted.
- [ ] Given an admin_super tries to `DELETE /admin/staff/:id` with their own ID, then `422 CANNOT_MODIFY_SELF` is returned.
- [ ] Given an admin staff member is suspended via `PATCH /admin/staff/:id` with `status: SUSPENDED`, then all active sessions for that staff member are immediately revoked and the staff member cannot log in until re-activated.
- [ ] Given `GET /admin/staff?role=admin_finance`, then only staff members with `role: admin_finance` are returned in the paginated response.
- [ ] Given `POST /admin/staff/:id/reset-password` is called for an existing staff member, then a `200 OK` response is returned and `reset_link_expires_at` is exactly 4 hours in the future; calling it a 6th time within an hour returns `429 RATE_LIMITED`.

## Dependencies

- EPIC-001 / STORY-003 - Admin staff authentication and MFA enrollment
- EPIC-001 / STORY-004 - Session revocation on staff suspension/removal
- EPIC-021 / STORY-003 - Audit log middleware captures all staff management actions

## Notes

- The `deleted_at` soft-delete means removed staff records are retained for audit purposes but excluded from all list queries by default. Add `include_deleted=true` query param for audit queries in a future iteration.
- The invitation email must contain a unique one-time-use token, not the admin_super's identity. The token is stored hashed in the AdminStaff record.
- Consider enforcing MFA setup as part of the invite completion flow for new admin_super invitations.
