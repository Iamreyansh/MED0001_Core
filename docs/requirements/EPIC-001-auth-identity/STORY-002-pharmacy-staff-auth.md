# STORY-002: Pharmacy Staff Authentication

| Attribute      | Value                                |
| -------------- | ------------------------------------ |
| **Story ID**   | STORY-002                            |
| **Epic**       | EPIC-001 - Authentication & Identity |
| **Priority**   | P0                                   |
| **Complexity** | M                                    |
| **Status**     | Draft                                |

---

## Overview

This story enables pharmacy staff and owners to authenticate into the Pharmacy Dashboard via email/phone + password, switch between multiple pharmacy contexts without re-authenticating, and use a quick 4-digit PIN login optimised for POS counter usage. A staff member may belong to multiple pharmacies with different roles in each (e.g., owner in one, cashier in another). The pharmacy context is embedded in the JWT payload so every downstream API call knows which pharmacy is active. POS PIN tokens are intentionally restricted to a short-lived 4-hour window and limited to POS-specific actions only.

## User Roles & Access

| Role             | Access Level | Description                                                                |
| ---------------- | ------------ | -------------------------------------------------------------------------- |
| pharmacy_owner   | Admin        | Full access to all pharmacy dashboard features; auto-assigned highest role |
| pharmacy_staff   | Write        | Logs in with credentials; can only access pharmacies they are assigned to  |
| admin_super      | Read         | Can view pharmacy staff accounts and force-reset passwords                 |
| admin_operations | Read         | Can view pharmacy staff list for operational purposes                      |

## Business Rules

1. A staff member is identified by either their email address or their phone number (+91 format) as the login identifier; both are unique platform-wide across all pharmacy staff.
2. Passwords must be a minimum of 8 characters and must contain at least one uppercase letter, one lowercase letter, one digit, and one special character. Passwords are hashed using bcrypt (cost 12) before storage.
3. After 5 consecutive failed login attempts for the same identifier within a 10-minute window, the account is locked for 30 minutes. The lockout counter resets on a successful login.
4. The access token payload includes `pharmacy_id` indicating the currently active pharmacy context. On initial login, the default pharmacy is the first pharmacy in the staff member's list ordered by `joined_at` ascending (or the sole pharmacy if only one).
5. A staff member with access to multiple pharmacies may call the `switch-pharmacy` endpoint to obtain a new access token scoped to a different pharmacy context without re-entering credentials. The existing refresh token is reused.
6. The POS PIN login endpoint issues a short-lived access token (TTL 4 hours) with a `token_scope: "pos"` claim. This token is blocked from all non-POS API endpoints (order creation, payments) at the middleware layer; it cannot be used to access staff management, settings, or reporting.
7. A POS PIN is exactly 4 digits. It is separate from the account password, is set by the pharmacy owner or manager, and is hashed using bcrypt before storage. A staff member without a POS PIN configured cannot use the POS PIN login flow.
8. The `switch-pharmacy` endpoint validates that the authenticated staff member actually has an active assignment to the requested `pharmacy_id` before issuing a new context token; otherwise `403 FORBIDDEN` is returned.
9. All failed login attempts (incorrect password, locked account, invalid PIN) are persisted to the audit log with the actor IP and user-agent for security monitoring.

## API Endpoints

### 1. Pharmacy Staff Login

```
POST /api/v1/auth/pharmacy/login
```

**Authentication:** None (public endpoint)
**Rate Limit:** 10 req/min per IP; lockout after 5 failed attempts per identifier

**Request Body (`application/json`):**

```json
{
	"identifier": "string - required, email address OR +91 phone number",
	"password": "string - required, min:8 chars",
	"pharmacy_id": "string - optional UUID, pre-select a specific pharmacy context on login"
}
```

**Success Response - `200 OK`:**

```json
{
	"success": true,
	"data": {
		"access_token": "eyJhbGciOiJSUzI1NiJ9...",
		"refresh_token": "dGhpcyBpcyBhIHJlZnJlc2ggdG9rZW4...",
		"token_type": "Bearer",
		"access_token_expires_in": 900,
		"refresh_token_expires_in": 604800,
		"active_pharmacy": {
			"id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
			"name": "Sri Rama Medicals",
			"logo_url": "https://cdn.nammamedmate.com/pharmacy/logos/abc.jpg",
			"city": "Bengaluru",
			"subscription_plan": "GROWTH"
		},
		"staff": {
			"id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
			"name": "Priya Sharma",
			"email": "priya@srirama.in",
			"phone": "+919876543210",
			"role": "pharmacy_owner",
			"mfa_enabled": false
		},
		"pharmacies": [
			{
				"id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
				"name": "Sri Rama Medicals",
				"role": "pharmacy_owner",
				"is_active": true
			},
			{
				"id": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
				"name": "Rama Pharmacy - Koramangala",
				"role": "pharmacist",
				"is_active": true
			}
		]
	},
	"meta": {}
}
```

**Error Responses:**

| HTTP | Error Code            | Condition                                                                       |
| ---- | --------------------- | ------------------------------------------------------------------------------- |
| 400  | `VALIDATION_ERROR`    | Missing or malformed identifier/password                                        |
| 401  | `INVALID_CREDENTIALS` | Password does not match                                                         |
| 403  | `ACCOUNT_LOCKED`      | Account locked due to too many failed attempts; includes `unlock_at` in details |
| 403  | `ACCOUNT_SUSPENDED`   | Staff account has been administratively suspended                               |
| 404  | `STAFF_NOT_FOUND`     | No staff account with the given identifier                                      |

---

### 2. Switch Pharmacy Context

```
POST /api/v1/auth/pharmacy/switch-pharmacy
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 30 req/min per user

**Request Body (`application/json`):**

```json
{
	"pharmacy_id": "string - required, UUID of the pharmacy to switch to"
}
```

**Success Response - `200 OK`:**

```json
{
	"success": true,
	"data": {
		"access_token": "eyJhbGciOiJSUzI1NiJ9...",
		"token_type": "Bearer",
		"access_token_expires_in": 900,
		"active_pharmacy": {
			"id": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
			"name": "Rama Pharmacy - Koramangala",
			"logo_url": null,
			"city": "Bengaluru",
			"subscription_plan": "STARTER"
		},
		"role_in_pharmacy": "pharmacist"
	},
	"meta": {}
}
```

**Error Responses:**

| HTTP | Error Code           | Condition                                                      |
| ---- | -------------------- | -------------------------------------------------------------- |
| 400  | `VALIDATION_ERROR`   | `pharmacy_id` is missing or not a valid UUID                   |
| 401  | `UNAUTHORIZED`       | Token missing or invalid                                       |
| 403  | `FORBIDDEN`          | Staff does not have an active assignment to requested pharmacy |
| 404  | `PHARMACY_NOT_FOUND` | Requested pharmacy does not exist                              |

---

### 3. POS PIN Login

```
POST /api/v1/auth/pharmacy/pos-pin
```

**Authentication:** None (public endpoint)
**Rate Limit:** 10 req/min per IP; lockout after 5 failed PIN attempts per staff_id

**Request Body (`application/json`):**

```json
{
	"pharmacy_id": "string - required, UUID of the pharmacy terminal",
	"staff_id": "string - required, UUID of the staff member",
	"pin": "string - required, exactly 4 digits"
}
```

**Success Response - `200 OK`:**

```json
{
	"success": true,
	"data": {
		"access_token": "eyJhbGciOiJSUzI1NiJ9...",
		"token_type": "Bearer",
		"token_scope": "pos",
		"access_token_expires_in": 14400,
		"staff": {
			"id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
			"name": "Kavya Nair",
			"role": "cashier"
		},
		"pharmacy": {
			"id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
			"name": "Sri Rama Medicals"
		}
	},
	"meta": {}
}
```

**Error Responses:**

| HTTP | Error Code           | Condition                                          |
| ---- | -------------------- | -------------------------------------------------- |
| 400  | `VALIDATION_ERROR`   | Missing fields or PIN is not exactly 4 digits      |
| 401  | `INVALID_PIN`        | PIN does not match the stored hash                 |
| 403  | `POS_PIN_NOT_SET`    | Staff member has no POS PIN configured             |
| 403  | `ACCOUNT_LOCKED`     | Too many failed PIN attempts; includes `unlock_at` |
| 403  | `STAFF_NOT_ASSIGNED` | Staff not assigned to the given pharmacy           |
| 404  | `STAFF_NOT_FOUND`    | No staff with given `staff_id`                     |

---

## Data Models

### PharmacyStaff

| Field                 | Type         | Constraints                      | Description                              |
| --------------------- | ------------ | -------------------------------- | ---------------------------------------- |
| id                    | UUID         | PK, auto-gen                     | Primary key                              |
| name                  | VARCHAR(100) | NOT NULL                         | Full display name                        |
| email                 | VARCHAR(255) | UNIQUE, nullable                 | Login email (unique platform-wide)       |
| phone                 | VARCHAR(15)  | UNIQUE, nullable                 | E.164 login phone (unique platform-wide) |
| password_hash         | VARCHAR(60)  | NOT NULL                         | bcrypt hash (cost 12)                    |
| pos_pin_hash          | VARCHAR(60)  | nullable                         | bcrypt hash of 4-digit POS PIN           |
| status                | VARCHAR(20)  | NOT NULL, default 'ACTIVE'       | ACTIVE \| SUSPENDED \| INVITED           |
| failed_login_attempts | SMALLINT     | NOT NULL, default 0              | Reset on success                         |
| locked_until          | TIMESTAMPTZ  | nullable                         | Lockout expiry; NULL = not locked        |
| last_login_at         | TIMESTAMPTZ  | nullable                         | Most recent successful login time        |
| invited_by            | UUID         | FK ? pharmacy_staff.id, nullable | Who sent the invite                      |
| created_at            | TIMESTAMPTZ  | NOT NULL, default NOW()          | Account creation time                    |
| updated_at            | TIMESTAMPTZ  | NOT NULL                         | Last update time                         |

### PharmacyStaffAssignment

| Field       | Type        | Constraints                      | Description                     |
| ----------- | ----------- | -------------------------------- | ------------------------------- |
| id          | UUID        | PK, auto-gen                     | Primary key                     |
| staff_id    | UUID        | FK ? pharmacy_staff.id, NOT NULL | Staff member                    |
| pharmacy_id | UUID        | FK ? pharmacies.id, NOT NULL     | Pharmacy being assigned to      |
| role_id     | UUID        | FK ? pharmacy_roles.id, NOT NULL | Role at this pharmacy           |
| is_active   | BOOLEAN     | NOT NULL, default true           | Whether assignment is active    |
| joined_at   | TIMESTAMPTZ | NOT NULL, default NOW()          | When the assignment was created |
| removed_at  | TIMESTAMPTZ | nullable                         | Soft-deactivation timestamp     |

## Acceptance Criteria

- [ ] Given valid credentials (email + password), when `POST /auth/pharmacy/login` is called, then a 200 response is returned containing both tokens, the active pharmacy context, and the full list of pharmacies the staff member is assigned to.
- [ ] Given a staff member assigned to 2 pharmacies and an active access token, when `POST /auth/pharmacy/switch-pharmacy` is called with the second pharmacy's ID, then a new access token is returned with the updated `pharmacy_id` claim and `role_in_pharmacy` reflects the staff's role at that pharmacy.
- [ ] Given a staff member's `staff_id`, `pharmacy_id`, and correct 4-digit PIN, when `POST /auth/pharmacy/pos-pin` is called, then a 200 response is returned with an access token with `token_scope: "pos"` and TTL of exactly 4 hours (14400 seconds).
- [ ] Given 5 consecutive wrong passwords within 10 minutes, when the 5th login fails, then the account is locked for 30 minutes, subsequent attempts return `403 ACCOUNT_LOCKED` with an `unlock_at` timestamp in the error details.
- [ ] Given a POS-scoped token, when it is used to call a non-POS endpoint (e.g., staff management, reporting), then `403 FORBIDDEN` is returned with error code `POS_TOKEN_RESTRICTED`.
- [ ] Given a staff member with no POS PIN configured, when `POST /auth/pharmacy/pos-pin` is called with their `staff_id`, then `403 POS_PIN_NOT_SET` is returned.
- [ ] Given a `switch-pharmacy` request for a `pharmacy_id` the staff member is not assigned to, when the endpoint is called, then `403 FORBIDDEN` is returned.

## Dependencies

- EPIC-001 / STORY-004 - JWT token issuance, refresh token management
- EPIC-001 / STORY-005 - Pharmacy role and permission definitions
- EPIC-010 / STORY-001 - Pharmacy onboarding (creates pharmacy record and first owner assignment)

## Notes

- The `identifier` field on the login endpoint should be normalised (lowercased for email, stripped of spaces for phone) before lookup.
- POS PIN should be set and managed via the Pharmacy Dashboard staff management UI (separate story in EPIC-010).
- Consider adding a `remember_device` flag in a future iteration to extend refresh token TTL on trusted devices.
- Failed login audit events should include the failed identifier, IP, and user-agent for security monitoring dashboards.
