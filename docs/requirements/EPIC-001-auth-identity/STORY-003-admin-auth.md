# STORY-003: Admin Staff Authentication & MFA

| Attribute      | Value                                |
| -------------- | ------------------------------------ |
| **Story ID**   | STORY-003                            |
| **Epic**       | EPIC-001 - Authentication & Identity |
| **Priority**   | P0                                   |
| **Complexity** | M                                    |
| **Status**     | Draft                                |

---

## Overview

This story provides secure authentication for all Namma MedMate admin team members, covering the Admin HQ panel. Admin login uses email + password as the first factor. For `admin_super`, a TOTP second factor (compatible with Google Authenticator, Authy, etc.) is mandatory before a token is issued. All other admin roles have TOTP as an optional enhancement. This story includes MFA enrollment (QR code + backup codes), MFA verification, and the administrative safeguards: IP-level logging of all failed attempts, account lockout after 5 failures, and 8-hour session expiry. The entire flow is audit-logged for compliance.

## User Roles & Access

| Role             | Access Level | Description                                                |
| ---------------- | ------------ | ---------------------------------------------------------- |
| admin_super      | Write        | Must complete MFA on every login; can set up and reset MFA |
| admin_operations | Write        | Optional MFA; standard credential login                    |
| admin_finance    | Write        | Optional MFA; standard credential login                    |
| admin_support    | Write        | Optional MFA; standard credential login                    |
| admin_compliance | Write        | Optional MFA; standard credential login                    |

## Business Rules

1. All admin logins begin with `POST /auth/admin/login` (email + password). If credentials are valid and MFA is not required/enrolled, tokens are issued immediately. If MFA is required or enrolled, the response returns `mfa_required: true` and a short-lived `mfa_challenge_token` (TTL 5 minutes) instead of the full session tokens.
2. `admin_super` accounts MUST complete TOTP verification on every login. If an `admin_super` does not have MFA set up, they are locked out until MFA is enrolled via a setup flow triggered by a temporary admin invite with forced MFA enrollment.
3. TOTP codes are 6-digit time-based codes with a 30-second time step, compliant with RFC 6238. Server accepts codes from the current 30-second window and one adjacent window (-30 seconds) to account for clock skew.
4. The `setup-mfa` endpoint may only be called by an authenticated admin who does not yet have MFA enrolled (`mfa_enabled: false`). It returns a TOTP URI (compatible with authenticator apps) and exactly 8 single-use backup codes. Backup codes are hashed (SHA-256) before storage.
5. A backup code can be used in place of a TOTP code at the `verify-mfa` endpoint. Once used, the backup code is invalidated immediately and the event is audit-logged. Backup codes do not expire but should be regenerated periodically.
6. After 5 consecutive failed login attempts (wrong password or wrong TOTP) within a 15-minute window, the admin account is locked for 30 minutes. The lockout is per account (not per IP). Lockout events are audit-logged with full IP and user-agent.
7. Every login attempt (success or failure) is written to the `admin_auth_events` table with `actor_email`, `ip_address`, `user_agent`, `event_type`, and `timestamp`. This table is append-only.
8. Admin access tokens have a TTL of 15 minutes and refresh tokens a TTL of 8 hours. Refresh tokens for admin sessions do not auto-renew beyond the 8-hour cap - once the refresh token expires, the admin must re-authenticate fully.
9. The `mfa_challenge_token` issued after a successful first-factor login is a short-lived, single-use JWT with scope `mfa_challenge` and TTL of 5 minutes. It is bound to the admin account ID and can only be exchanged at `verify-mfa`. It cannot be used for any other API call.

## API Endpoints

### 1. Admin Login (First Factor)

```
POST /api/v1/auth/admin/login
```

**Authentication:** None (public endpoint)
**Rate Limit:** 20 req/min per IP; account lockout after 5 failed attempts per email

**Request Body (`application/json`):**

```json
{
	"email": "string - required, valid email address",
	"password": "string - required, min:8 chars"
}
```

**Success Response - `200 OK` (MFA not enrolled or not required):**

```json
{
	"success": true,
	"data": {
		"access_token": "eyJhbGciOiJSUzI1NiJ9...",
		"refresh_token": "dGhpcyBpcyBhIHJlZnJlc2ggdG9rZW4...",
		"token_type": "Bearer",
		"access_token_expires_in": 900,
		"refresh_token_expires_in": 28800,
		"mfa_required": false,
		"admin": {
			"id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
			"name": "Sundar Rajan",
			"email": "sundar@namma-medmate.in",
			"role": "admin_operations",
			"mfa_enabled": false
		}
	},
	"meta": {}
}
```

**Success Response - `200 OK` (MFA required or enrolled):**

```json
{
	"success": true,
	"data": {
		"mfa_required": true,
		"mfa_challenge_token": "eyJhbGciOiJSUzI1NiJ9.mfa-challenge...",
		"mfa_challenge_expires_in": 300,
		"admin_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6"
	},
	"meta": {}
}
```

**Error Responses:**

| HTTP | Error Code                  | Condition                                                 |
| ---- | --------------------------- | --------------------------------------------------------- |
| 400  | `VALIDATION_ERROR`          | Missing or malformed email/password                       |
| 401  | `INVALID_CREDENTIALS`       | Password does not match                                   |
| 403  | `ACCOUNT_LOCKED`            | Too many failed attempts; includes `unlock_at` in details |
| 403  | `ACCOUNT_SUSPENDED`         | Admin account suspended by admin_super                    |
| 403  | `MFA_ENROLLMENT_REQUIRED`   | `admin_super` has no TOTP secret enrolled yet             |
| 404  | `ADMIN_NOT_FOUND`           | No admin account with the given email                     |
| 429  | `IP_RATE_LIMITED`           | IP login rate limit exceeded (20/min)                     |

---

### 2. Verify MFA (Second Factor)

```
POST /api/v1/auth/admin/verify-mfa
```

**Authentication:** Bearer JWT - `mfa_challenge` scope token (from login step)
**Rate Limit:** 10 req/min per IP

**Request Body (`application/json`):**

```json
{
	"mfa_challenge_token": "string - required, the challenge token from login response",
	"code": "string - required, 6-digit TOTP code OR 8-char backup code"
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
		"refresh_token_expires_in": 28800,
		"used_backup_code": false,
		"admin": {
			"id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
			"name": "Ayesha Siddiqui",
			"email": "ayesha@namma-medmate.in",
			"role": "admin_super",
			"mfa_enabled": true,
			"backup_codes_remaining": 7
		}
	},
	"meta": {}
}
```

**Error Responses:**

| HTTP | Error Code                | Condition                                     |
| ---- | ------------------------- | --------------------------------------------- |
| 400  | `VALIDATION_ERROR`        | Missing or malformed code or challenge token  |
| 400  | `INVALID_MFA_CODE`        | TOTP code is incorrect or expired             |
| 400  | `INVALID_BACKUP_CODE`     | Backup code does not match or already used    |
| 401  | `CHALLENGE_TOKEN_EXPIRED` | `mfa_challenge_token` has expired (5 min TTL) |
| 401  | `CHALLENGE_TOKEN_INVALID` | Token tampered, wrong scope, reused, or mismatch with Bearer |
| 403  | `ACCOUNT_LOCKED`          | Account locked due to too many MFA failures   |
| 429  | `IP_RATE_LIMITED`         | IP MFA rate limit exceeded (10/min)           |

---

### 3. Setup MFA (TOTP Enrollment)

```
POST /api/v1/auth/admin/setup-mfa
```

**Authentication:** Bearer JWT - any admin role (only works if `mfa_enabled: false`)
**Rate Limit:** 5 req/hour per user

**Request Body (`application/json`):**

```json
{}
```

**Success Response - `200 OK`:**

```json
{
	"success": true,
	"data": {
		"totp_uri": "otpauth://totp/NammaMedMate:ayesha%40namma-medmate.in?secret=JBSWY3DPEHPK3PXP&issuer=NammaMedMate&algorithm=SHA1&digits=6&period=30",
		"totp_secret": "JBSWY3DPEHPK3PXP",
		"backup_codes": [
			"A1B2-C3D4",
			"E5F6-G7H8",
			"I9J0-K1L2",
			"M3N4-O5P6",
			"Q7R8-S9T0",
			"U1V2-W3X4",
			"Y5Z6-A7B8",
			"C9D0-E1F2"
		]
	},
	"meta": {}
}
```

**Error Responses:**

| HTTP | Error Code             | Condition                                                  |
| ---- | ---------------------- | ---------------------------------------------------------- |
| 400  | `MFA_ALREADY_ENROLLED` | Admin already has `mfa_enabled: true`                      |
| 401  | `UNAUTHORIZED`         | Token missing or invalid                                   |
| 403  | `FORBIDDEN`            | Role not permitted (should not happen for any valid admin) |
| 429  | `IP_RATE_LIMITED`      | Setup rate limit exceeded (5/hour per user)                |

---

## Data Models

### AdminStaff

| Field                 | Type         | Constraints                   | Description                                                                           |
| --------------------- | ------------ | ----------------------------- | ------------------------------------------------------------------------------------- |
| id                    | UUID         | PK, auto-gen                  | Primary key                                                                           |
| name                  | VARCHAR(100) | NOT NULL                      | Full display name                                                                     |
| email                 | VARCHAR(255) | UNIQUE, NOT NULL              | Login email; unique platform-wide                                                     |
| password_hash         | VARCHAR(60)  | NOT NULL                      | bcrypt hash (cost 12)                                                                 |
| role                  | VARCHAR(30)  | NOT NULL                      | admin_super \| admin_operations \| admin_finance \| admin_support \| admin_compliance |
| status                | VARCHAR(20)  | NOT NULL, default 'ACTIVE'    | ACTIVE \| SUSPENDED \| INVITED                                                        |
| mfa_enabled           | BOOLEAN      | NOT NULL, default false       | Whether TOTP is enrolled                                                              |
| totp_secret           | VARCHAR(32)  | nullable, encrypted           | TOTP base32 secret; AES-256-GCM encrypted at rest                                     |
| backup_codes          | JSONB        | nullable                      | Array of hashed (SHA-256) backup codes with used_at timestamps                        |
| failed_login_attempts | SMALLINT     | NOT NULL, default 0           | Resets on successful login                                                            |
| locked_until          | TIMESTAMPTZ  | nullable                      | Lockout expiry                                                                        |
| last_login_at         | TIMESTAMPTZ  | nullable                      | Most recent successful full login (post-MFA)                                          |
| last_active_at        | TIMESTAMPTZ  | nullable                      | Most recent API request timestamp                                                     |
| invited_by            | UUID         | FK ? admin_staff.id, nullable | Who created this account                                                              |
| created_at            | TIMESTAMPTZ  | NOT NULL, default NOW()       | Account creation timestamp                                                            |
| updated_at            | TIMESTAMPTZ  | NOT NULL                      | Last update timestamp                                                                 |

### AdminAuthEvent

| Field      | Type        | Constraints                  | Description                                                                            |
| ---------- | ----------- | ---------------------------- | -------------------------------------------------------------------------------------- |
| id         | UUID        | PK, auto-gen                 | Primary key                                                                            |
| admin_id   | UUID        | FK ? admin_staff.id, indexed | The admin account involved                                                             |
| event_type | VARCHAR(40) | NOT NULL                     | LOGIN_SUCCESS \| LOGIN_FAILED \| MFA_SUCCESS \| MFA_FAILED \| ACCOUNT_LOCKED \| LOGOUT |
| ip_address | INET        | NOT NULL                     | Requesting IP address                                                                  |
| user_agent | TEXT        | nullable                     | HTTP User-Agent header                                                                 |
| metadata   | JSONB       | nullable                     | Additional event-specific context                                                      |
| created_at | TIMESTAMPTZ | NOT NULL, default NOW()      | Event timestamp (append-only)                                                          |

## Acceptance Criteria

- [ ] Given a valid admin email and password for an `admin_super` with MFA enrolled, when `POST /auth/admin/login` is called, then `200 OK` is returned with `mfa_required: true` and a `mfa_challenge_token` with a 5-minute TTL; no full session tokens are issued at this stage.
- [ ] Given a valid `mfa_challenge_token` and a correct TOTP code, when `POST /auth/admin/verify-mfa` is called, then `200 OK` is returned with a full access token (15 min TTL) and refresh token (8 hour TTL), and the login event is recorded in `AdminAuthEvent`.
- [ ] Given an admin with `mfa_enabled: false`, when `POST /auth/admin/setup-mfa` is called, then a `totp_uri`, `totp_secret`, and exactly 8 unique backup codes are returned; calling setup-mfa again while `mfa_enabled: false` should replace the previous unenrolled secret.
- [ ] Given a valid backup code, when `POST /auth/admin/verify-mfa` is called using it, then `used_backup_code: true` is returned, the code is permanently invalidated in the backup_codes JSONB array, and `backup_codes_remaining` is decremented.
- [ ] Given 5 consecutive failed login attempts for the same admin email, when the 5th attempt fails, then the account is locked, `403 ACCOUNT_LOCKED` is returned on the 5th call, and all attempts are recorded in `AdminAuthEvent` with the correct `ip_address`.
- [ ] Given an `admin_super` account with `mfa_enabled: false` (not yet enrolled), when login credentials are correct, then the response must NOT issue full session tokens; instead a specific error or forced-enrollment response must be returned to prevent bypassing MFA.
- [ ] Given an expired `mfa_challenge_token` (older than 5 minutes), when `POST /auth/admin/verify-mfa` is called, then `401 CHALLENGE_TOKEN_EXPIRED` is returned.

## Dependencies

- EPIC-001 / STORY-004 - JWT token issuance, MFA challenge token scoping
- EPIC-021 / STORY-001 - Admin staff creation and invitation flow

## Notes

- TOTP secrets must be encrypted at rest (AES-256-GCM) in the database. The encryption key must be stored in AWS Secrets Manager (`MEDMATE_SECRETS_MFA_ARN` → `encryption_key_base64`), never as a known default in deployed profiles.
- Clients render the authenticator QR locally from `totp_uri` / `totp_secret` — the API does not call third-party QR hosts (avoids leaking the TOTP secret).
- In production, the setup-mfa flow must be followed by an activation step (first successful TOTP verification) before `mfa_enabled` is set to `true`. This prevents lockout due to misconfigured authenticator app.
- Rate-limit the `setup-mfa` endpoint aggressively (5/hour) to prevent secret regeneration attacks.
- Backup code format `XXXX-XXXX` (4 alphanumeric + hyphen + 4 alphanumeric) balances human readability with entropy.
- Failed password and MFA attempts share the same lockout counter; password success does not reset the counter until full session issuance (post-MFA).
- `verify-mfa` requires `Authorization: Bearer <mfa_challenge_token>` and the same token in the body; challenge JTIs are single-use via a shared Redis revocation store.
