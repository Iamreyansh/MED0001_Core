# STORY-001: Customer Mobile OTP Authentication

| Attribute      | Value                                |
| -------------- | ------------------------------------ |
| **Story ID**   | STORY-001                            |
| **Epic**       | EPIC-001 - Authentication & Identity |
| **Priority**   | P0                                   |
| **Complexity** | M                                    |
| **Status**     | Draft                                |

---

## Overview

This story delivers the end-to-end OTP-based login flow for customers accessing the Namma MedMate mobile app. A customer enters their Indian mobile number, receives a 6-digit OTP via SMS, and on successful verification receives a JWT access token, a refresh token, and their full profile. New customers (first-time login) are automatically flagged with `is_new_user: true` to trigger onboarding profile creation in the app. The story covers the full rate-limiting and cooldown logic to prevent SMS abuse and brute-force OTP guessing.

## User Roles & Access

| Role          | Access Level | Description                                                         |
| ------------- | ------------ | ------------------------------------------------------------------- |
| customer      | Write        | Sends OTP to own phone and verifies it to obtain a session          |
| admin_super   | Read         | Can view OTP session records in audit log for fraud investigation   |
| admin_support | Read         | Can view OTP attempt counts per phone for customer support purposes |

## Business Rules

1. The phone number must be a valid Indian mobile number in E.164 format (+91XXXXXXXXXX), where the 10-digit national number starts with 6, 7, 8, or 9.
2. OTP is a cryptographically random 6-digit numeric code. It is never stored in plaintext - only its bcrypt hash (cost 10) is persisted in the `otp_sessions` table.
3. An OTP session is valid for exactly 10 minutes from the time of creation (`expires_at = created_at + 10 min`). Expired sessions are rejected regardless of OTP correctness.
4. A maximum of 3 verification attempts are allowed per OTP session. After the 3rd failed attempt, the session is invalidated (`locked_at` is set) and a 30-minute cooldown is imposed on that phone number before a new OTP can be requested.
5. The send-OTP endpoint enforces a hard rate limit of 3 OTP requests per phone number per rolling 60-minute window, tracked in Redis. Exceeding this limit returns `429 OTP_RATE_LIMITED` and the response includes `retry_after_seconds`.
6. On successful OTP verification, the OTP session is marked as used (`verified_at` is set) and cannot be reused. The session record is retained for 30 days for audit purposes.
7. If the phone number has never been seen before, the verification response includes `is_new_user: true` and a partial customer record is created with only the phone number; the customer is expected to complete their profile in the onboarding flow.
8. The optional `device_token` field in the verify request is stored for FCM/APNs push notifications. If a new `device_token` is provided on subsequent logins, it replaces the previous token for that device.
9. IP-level rate limiting applies independently: a single IP may not trigger more than 10 send-OTP requests per hour across any phone numbers, to prevent enumeration attacks.

## API Endpoints

### 1. Send OTP

```
POST /api/v1/auth/customer/send-otp
```

**Authentication:** None (public endpoint)
**Rate Limit:** 3 req/hour per phone number; 10 req/hour per IP

**Request Body (`application/json`):**

```json
{
	"phone": "string - required, E.164 format, must match ^\\+91[6-9]\\d{9}$",
	"device_info": {
		"platform": "string - optional, enum: ios|android|web",
		"device_id": "string - optional, max:255, unique device identifier",
		"app_version": "string - optional, semver e.g. 1.2.3"
	}
}
```

**Success Response - `200 OK`:**

```json
{
	"success": true,
	"data": {
		"session_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
		"phone": "+919876543210",
		"expires_at": "2026-07-24T08:10:00Z",
		"resend_allowed_at": "2026-07-24T08:05:00Z",
		"attempts_remaining": 3
	},
	"meta": {}
}
```

**Error Responses:**

| HTTP | Error Code          | Condition                                                     |
| ---- | ------------------- | ------------------------------------------------------------- |
| 400  | `VALIDATION_ERROR`  | Phone is missing, malformed, or not an Indian mobile number   |
| 429  | `OTP_RATE_LIMITED`  | Exceeded 3 OTPs/hour for this phone or 30-min cooldown active |
| 429  | `IP_RATE_LIMITED`   | IP exceeded 10 send-OTP requests/hour                         |
| 503  | `SMS_GATEWAY_ERROR` | Upstream SMS provider unavailable                             |

---

### 2. Verify OTP

```
POST /api/v1/auth/customer/verify-otp
```

**Authentication:** None (public endpoint)
**Rate Limit:** 10 req/min per IP

**Request Body (`application/json`):**

```json
{
	"session_id": "string - required, UUID of the OTP session from send-otp response",
	"phone": "string - required, must match the session's phone",
	"otp": "string - required, exactly 6 digits",
	"device_token": "string - optional, max:512, FCM/APNs push notification token"
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
		"refresh_token_expires_in": 2592000,
		"is_new_user": false,
		"customer": {
			"id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
			"phone": "+919876543210",
			"name": "Ramesh Kumar",
			"avatar_url": "https://cdn.nammamedmate.com/avatars/abc123.jpg",
			"date_of_birth": "1988-05-14",
			"gender": "MALE",
			"preferred_language": "kn",
			"segment": "LOYAL",
			"wallet_balance": 125.5,
			"loyalty_points": 38,
			"created_at": "2025-01-10T06:30:00Z"
		}
	},
	"meta": {}
}
```

**Error Responses:**

| HTTP | Error Code              | Condition                                       |
| ---- | ----------------------- | ----------------------------------------------- |
| 400  | `VALIDATION_ERROR`      | Missing or malformed fields                     |
| 400  | `OTP_INVALID`           | OTP does not match the session's hash           |
| 400  | `OTP_EXPIRED`           | Session `expires_at` is in the past             |
| 400  | `OTP_SESSION_LOCKED`    | Session locked after 3 failed attempts          |
| 404  | `OTP_SESSION_NOT_FOUND` | `session_id` does not exist                     |
| 409  | `OTP_ALREADY_USED`      | Session already verified (`verified_at` is set) |
| 429  | `IP_RATE_LIMITED`       | IP exceeded verify rate limit                   |

---

## Data Models

### OtpSession

| Field       | Type        | Constraints             | Description                               |
| ----------- | ----------- | ----------------------- | ----------------------------------------- |
| id          | UUID        | PK, auto-gen            | Primary key                               |
| phone       | VARCHAR(15) | NOT NULL, indexed       | E.164 format phone number                 |
| otp_hash    | VARCHAR(60) | NOT NULL                | bcrypt hash (cost 10) of the 6-digit OTP  |
| attempts    | SMALLINT    | NOT NULL, default 0     | Number of failed verification attempts    |
| device_info | JSONB       | nullable                | Platform, device_id, app_version snapshot |
| expires_at  | TIMESTAMPTZ | NOT NULL                | OTP expiry (created_at + 10 minutes)      |
| verified_at | TIMESTAMPTZ | nullable                | Set when OTP is successfully verified     |
| locked_at   | TIMESTAMPTZ | nullable                | Set when max attempts exceeded            |
| created_at  | TIMESTAMPTZ | NOT NULL, default NOW() | Session creation timestamp                |

### Customer (partial - full model in EPIC-002 STORY-001)

| Field         | Type        | Constraints             | Description                         |
| ------------- | ----------- | ----------------------- | ----------------------------------- |
| id            | UUID        | PK, auto-gen            | Primary key                         |
| phone         | VARCHAR(15) | UNIQUE, NOT NULL        | E.164 format - login identifier     |
| device_tokens | TEXT[]      | nullable                | Array of current FCM/APNs tokens    |
| created_at    | TIMESTAMPTZ | NOT NULL, default NOW() | First login / account creation time |

## Acceptance Criteria

- [ ] Given a valid Indian mobile number, when `send-otp` is called, then a 6-digit OTP is delivered via SMS within 5 seconds and a session record is created with a non-plaintext OTP hash and `expires_at` 10 minutes in the future.
- [ ] Given a valid `session_id`, matching `phone`, and correct `otp`, when `verify-otp` is called within the expiry window, then a JWT access token (15 min TTL) and refresh token (30 day TTL) are returned along with the customer profile; `verified_at` is set on the session.
- [ ] Given a valid session and correct `otp`, when `verify-otp` is called after `expires_at`, then `400 OTP_EXPIRED` is returned and no tokens are issued.
- [ ] Given 3 consecutive wrong OTPs against the same session, when the 3rd verification fails, then the session `locked_at` is set, subsequent attempts return `400 OTP_SESSION_LOCKED`, and `send-otp` returns `429 OTP_RATE_LIMITED` for 30 minutes for that phone.
- [ ] Given the same phone number has had 3 send-OTP calls within the past 60 minutes, when a 4th `send-otp` is called, then `429 OTP_RATE_LIMITED` is returned with a `retry_after_seconds` field indicating the wait time.
- [ ] Given a phone number that has never been seen on the platform, when OTP verification succeeds, then `is_new_user: true` is returned in the response and a minimal Customer record is created with only the phone number populated.
- [ ] Given a successfully verified OTP session, when `verify-otp` is called again with the same `session_id`, then `409 OTP_ALREADY_USED` is returned.

## Dependencies

- EPIC-000 / Infrastructure - SMS gateway integration (MSG91 or Twilio) with fallback
- EPIC-001 / STORY-004 - JWT token issuance and refresh token storage strategy

## Notes

- OTP delivery should use a fast SMS route (DLT-registered template required for India).
- In staging/test environments, a magic OTP `123456` must be accepted for any `+91` number matching the test range `+919999900000`-`+919999900099`.
- Consider implementing OTP via WhatsApp as a fallback if SMS fails after 30 seconds.
- Redis keys for rate limiting: `otp:phone:{phone}:count` (TTL 3600s), `otp:ip:{ip}:count` (TTL 3600s), `otp:cooldown:{phone}` (TTL 1800s).
