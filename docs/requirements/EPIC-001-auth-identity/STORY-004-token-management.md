# STORY-004: JWT Token Management & Session Control

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-004 |
| **Epic** | EPIC-001 - Authentication & Identity |
| **Priority** | P0 |
| **Complexity** | L |
| **Status** | Draft |

---

## Overview

This story defines the full lifecycle of JWT access tokens and refresh tokens across all platform roles, including token refresh with rotation, single-session logout, global session revocation, the universal `GET /me` endpoint, and device/session management. The platform uses a refresh token rotation strategy: each time a refresh token is exchanged for a new access token, the old refresh token is simultaneously invalidated and a new refresh token is issued. This prevents replay attacks on compromised refresh tokens. Active sessions are tracked in Redis (for speed) and mirrored to the database (for audit and cross-device management).

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| customer | Write | Can refresh tokens, logout, list/revoke own sessions |
| pharmacy_owner | Write | Can refresh tokens, logout, list/revoke own sessions |
| pharmacy_staff | Write | Can refresh tokens, logout, list/revoke own sessions |
| rider | Write | Can refresh tokens, logout |
| admin_super | Admin | Can view and revoke sessions for any user |
| admin_operations | Read | Can view sessions for pharmacy/rider users |
| admin_support | Read | Can view sessions for customer users |

## Business Rules

1. Access tokens are short-lived JWTs (RS256 signed) with a TTL of 15 minutes for all roles. They are stateless and validated via signature + `exp` claim on every request.
2. Refresh tokens are opaque random strings (32 bytes of CSPRNG, base64url encoded). They are stored hashed (SHA-256) in the `sessions` table. TTLs are: customers - 30 days; pharmacy_owner and pharmacy_staff - 7 days; rider - 7 days; admin (all roles) - 8 hours (aligned with admin session hard cap).
3. Token rotation is mandatory: every call to `POST /auth/refresh` issues a brand-new refresh token and access token, and immediately invalidates the provided refresh token in the sessions table (`rotated_at` is set). Attempting to reuse a rotated (already-exchanged) refresh token returns `401 REFRESH_TOKEN_REUSED` and triggers a security event that revokes ALL sessions for that user (rotation replay protection).
4. The JWT payload includes: `sub` (user UUID), `role` (e.g., `customer`, `admin_super`), `pharmacy_id` (for pharmacy roles, nullable), `token_scope` (e.g., `full`, `pos`, `mfa_challenge`), `iat`, and `exp`. It does NOT contain sensitive data (name, phone, email).
5. `POST /auth/logout` invalidates only the specific session associated with the provided refresh token. The access token continues to be technically valid until its `exp` but the session record is deleted; downstream services may optionally consult a short-lived Redis revocation list.
6. `POST /auth/logout-all` invalidates all active sessions for the authenticated user regardless of role. This is triggered by the user or by security events (e.g., rotation replay detected).
7. `GET /auth/me` returns the currently authenticated user's profile based on the `role` claim in the JWT, fetching from the appropriate table (customers, pharmacy_staff, or admin_staff).
8. `GET /auth/sessions` returns paginated active sessions including device OS, browser, IP, country, and `last_active_at`. Sessions inactive for more than their TTL are automatically excluded.
9. A session may be explicitly revoked by the authenticated user via `DELETE /auth/sessions/:session_id` even before it expires. Admin users with appropriate permissions may revoke sessions for any user via the admin API.

## API Endpoints

### 1. Refresh Token

```
POST /api/v1/auth/refresh
```

**Authentication:** None required at header level; the refresh_token in the body IS the credential
**Rate Limit:** 30 req/min per IP

**Request Body (`application/json`):**
```json
{
  "refresh_token": "string - required, the opaque refresh token string"
}
```

**Success Response - `200 OK`:**
```json
{
  "success": true,
  "data": {
    "access_token": "eyJhbGciOiJSUzI1NiJ9...",
    "refresh_token": "VGhpcyBpcyBhIG5ldyByZWZyZXNoIHRva2Vu...",
    "token_type": "Bearer",
    "access_token_expires_in": 900,
    "refresh_token_expires_in": 2592000
  },
  "meta": {}
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `VALIDATION_ERROR` | `refresh_token` field missing |
| 401 | `REFRESH_TOKEN_INVALID` | Token not found in sessions table |
| 401 | `REFRESH_TOKEN_EXPIRED` | Token TTL exceeded |
| 401 | `REFRESH_TOKEN_REUSED` | Token was already rotated - all sessions for this user are revoked |
| 403 | `ACCOUNT_SUSPENDED` | User account has been suspended |

---

### 2. Logout (Current Session)

```
POST /api/v1/auth/logout
```

**Authentication:** Bearer JWT - any role
**Rate Limit:** 30 req/min per user

**Request Body (`application/json`):**
```json
{
  "refresh_token": "string - required, the refresh token to invalidate"
}
```

**Success Response - `200 OK`:**
```json
{
  "success": true,
  "data": {
    "message": "Session terminated successfully."
  },
  "meta": {}
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `VALIDATION_ERROR` | `refresh_token` field missing |
| 401 | `UNAUTHORIZED` | Access token missing or invalid |
| 404 | `SESSION_NOT_FOUND` | Refresh token not found or already invalidated |

---

### 3. Logout All Sessions

```
POST /api/v1/auth/logout-all
```

**Authentication:** Bearer JWT - any role
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
    "sessions_revoked": 3,
    "message": "All sessions have been terminated."
  },
  "meta": {}
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 401 | `UNAUTHORIZED` | Token missing or invalid |

---

### 4. Get Current User

```
GET /api/v1/auth/me
```

**Authentication:** Bearer JWT - any role
**Rate Limit:** 60 req/min per user

**Success Response - `200 OK` (customer example):**
```json
{
  "success": true,
  "data": {
    "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "role": "customer",
    "phone": "+919876543210",
    "name": "Ramesh Kumar",
    "avatar_url": "https://cdn.namma-medmate.in/avatars/abc123.jpg",
    "preferred_language": "kn",
    "segment": "LOYAL",
    "wallet_balance": 125.50,
    "loyalty_points": 38
  },
  "meta": {}
}
```

**Success Response - `200 OK` (pharmacy staff example):**
```json
{
  "success": true,
  "data": {
    "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "role": "pharmacy_owner",
    "name": "Priya Sharma",
    "email": "priya@srirama.in",
    "phone": "+919876543211",
    "active_pharmacy": {
      "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "name": "Sri Rama Medicals"
    },
    "permissions": ["orders:read", "orders:fulfill", "inventory:*", "staff:*"]
  },
  "meta": {}
}
```

**Success Response - `200 OK` (admin example):**
```json
{
  "success": true,
  "data": {
    "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "role": "admin_super",
    "name": "Ayesha Siddiqui",
    "email": "ayesha@namma-medmate.in",
    "mfa_enabled": true,
    "last_login_at": "2026-07-24T01:00:00Z"
  },
  "meta": {}
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 401 | `UNAUTHORIZED` | Token missing, invalid, or expired |

---

### 5. List Active Sessions

```
GET /api/v1/auth/sessions
```

**Authentication:** Bearer JWT - any role
**Rate Limit:** 20 req/min per user

**Query Parameters:**
| Param | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| page | integer | No | 1 | Page number |
| limit | integer | No | 20 | Results per page, max 100 |

**Success Response - `200 OK`:**
```json
{
  "success": true,
  "data": [
    {
      "session_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
      "device": {
        "platform": "android",
        "device_id": "abc123def456",
        "app_version": "2.1.0"
      },
      "ip_address": "106.51.0.1",
      "country": "IN",
      "city": "Bengaluru",
      "user_agent": "MedMate/2.1.0 (Android 14)",
      "created_at": "2026-07-23T10:00:00Z",
      "last_active_at": "2026-07-24T01:00:00Z",
      "expires_at": "2026-08-22T10:00:00Z",
      "is_current": true
    }
  ],
  "meta": {
    "page": 1,
    "limit": 20,
    "total": 2,
    "has_next": false
  }
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 401 | `UNAUTHORIZED` | Token missing or invalid |

---

### 6. Revoke Specific Session

```
DELETE /api/v1/auth/sessions/:session_id
```

**Authentication:** Bearer JWT - any role
**Rate Limit:** 10 req/min per user

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| :session_id | UUID | ID of the session to revoke |

**Success Response - `200 OK`:**
```json
{
  "success": true,
  "data": {
    "session_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "message": "Session revoked."
  },
  "meta": {}
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 401 | `UNAUTHORIZED` | Token missing or invalid |
| 403 | `FORBIDDEN` | Attempting to revoke another user's session (non-admin) |
| 404 | `SESSION_NOT_FOUND` | Session does not exist or is already revoked |

---

## Data Models

### Session

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | UUID | PK, auto-gen | Primary key; this is the `session_id` |
| user_id | UUID | NOT NULL, indexed | References the user; no FK (cross-table) |
| user_type | VARCHAR(20) | NOT NULL | customer \| pharmacy_staff \| admin_staff \| rider |
| refresh_token_hash | VARCHAR(64) | NOT NULL, UNIQUE | SHA-256 hex of the opaque refresh token |
| pharmacy_id | UUID | nullable | Active pharmacy context at time of session creation |
| token_scope | VARCHAR(20) | NOT NULL, default 'full' | full \| pos |
| device_info | JSONB | nullable | platform, device_id, app_version |
| ip_address | INET | NOT NULL | IP at session creation |
| user_agent | TEXT | nullable | User-Agent at session creation |
| country | CHAR(2) | nullable | ISO 3166-1 alpha-2 from IP geolocation |
| city | VARCHAR(100) | nullable | City from IP geolocation |
| created_at | TIMESTAMPTZ | NOT NULL, default NOW() | Session creation (login) time |
| last_active_at | TIMESTAMPTZ | NOT NULL | Updated on each token refresh |
| expires_at | TIMESTAMPTZ | NOT NULL | Refresh token expiry |
| rotated_at | TIMESTAMPTZ | nullable | When the refresh token was rotated (for replay detection) |
| revoked_at | TIMESTAMPTZ | nullable | Explicit revocation timestamp |

## Acceptance Criteria

- [ ] Given a valid, unexpired refresh token, when `POST /auth/refresh` is called, then a new access token (15 min TTL) and a new refresh token are returned; the old refresh token's `rotated_at` is set and it can no longer be used.
- [ ] Given a refresh token that has already been rotated (rotated_at is set), when `POST /auth/refresh` is called again with the old token, then `401 REFRESH_TOKEN_REUSED` is returned and ALL sessions for that user are immediately revoked.
- [ ] Given an authenticated customer, when `GET /auth/me` is called with a valid access token, then the customer profile is returned including `role: "customer"`, wallet balance, and loyalty points.
- [ ] Given an authenticated admin_super, when `GET /auth/me` is called, then the admin profile is returned with `role: "admin_super"` and `mfa_enabled: true`.
- [ ] Given a user with 3 active sessions, when `POST /auth/logout-all` is called, then `sessions_revoked: 3` is returned and all 3 session records have `revoked_at` set.
- [ ] Given an active session belonging to user A, when user B (same role, not admin) tries to `DELETE /auth/sessions/:session_id` using that session ID, then `403 FORBIDDEN` is returned.
- [ ] Given a customer refresh token with a 30-day TTL, when it is used at day 31, then `401 REFRESH_TOKEN_EXPIRED` is returned.

## Dependencies

- EPIC-001 / STORY-001 - Customer OTP auth issues the first session
- EPIC-001 / STORY-002 - Pharmacy staff auth issues the first session
- EPIC-001 / STORY-003 - Admin auth issues sessions post-MFA

## Notes

- Redis key pattern for access token revocation list: `revoked_access:{jti}` with TTL matching remaining token lifetime. This is only populated for tokens that need early invalidation (logout, account suspension).
- IP geolocation can use `geoip-lite` (local MaxMind database) for low-latency enrichment without an external API call.
- The `is_current` field in the session list is determined by comparing the session's `refresh_token_hash` against the hash of the refresh token present in the current request.
- Consider adding a `trusted_device` flag in a future iteration for device-based extended TTLs.
