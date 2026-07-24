# STORY-005: Notification Preferences

| Field | Value |
|-------|-------|
| Story ID | EPIC-017-STORY-005 |
| Epic | EPIC-017 Notifications and Communications |
| Title | Notification Preferences |
| Priority | P1 |
| Status | In Development |
| Role | customer, pharmacy_owner |
| Last Updated | 2026-07-24 |

## Overview

The Notification Preferences story allows customers and pharmacies to control which notification channels (push, SMS, WhatsApp, email) and categories (order updates, promotions, refill reminders, offers) they receive communications on. Preferences are persisted per user and consulted by the notification dispatch layer before each send. Account-critical and order-update categories are mandatory and cannot be disabled. A public unsubscribe endpoint processes one-click email unsubscription from marketing emails using a signed token.

## User Roles

| Role | Access |
|------|--------|
| customer | Get and update their own notification preferences |
| pharmacy_owner | Get and update their pharmacy's notification preferences |
| pharmacy_staff | Read-only access to pharmacy preferences |
| admin_super | View any user's preferences (read-only) |
| Public (no auth) | POST /unsubscribe with valid token |

## Business Rules

1. **Mandatory Categories**: `order_updates` and `account_critical` categories cannot be disabled. Any PATCH request attempting to set these to `false` receives a `422 CANNOT_DISABLE_MANDATORY_CATEGORY` error.
2. **Default State**: New users start with all channels enabled and all categories enabled. Preferences are created with defaults when a customer/pharmacy first registers.
3. **Channel Independence**: Channel and category preferences are orthogonal. A customer can enable push but disable SMS independently. Category filters apply after channel selection.
4. **WhatsApp Dual Opt-Out**: A customer may have opted out of WhatsApp via the in-app preference toggle OR via a WA-native "STOP" reply. The notification service checks both the preferences table AND the `whatsapp_optouts` table before sending.
5. **Unsubscribe Token**: The unsubscribe link in emails contains a JWT-signed token with `sub: customer_id`, `email: customer_email`, `exp: now + 7 days`. On POST /notifications/unsubscribe, the token is verified, and the customer's email preference for `MARKETING` is set to `false`.
6. **Refill Reminder Linkage**: The `refill_reminders` category preference is also governed by the patient's medicine schedule settings. If reminders are turned off at the schedule level, they do not fire even if the preference is enabled globally. Global preference OFF takes precedence.
7. **Pharmacy Preferences Scope**: Pharmacy preferences are associated with the pharmacy entity (not a specific staff user). The pharmacy owner manages them. Pharmacy-specific categories: `order_alerts`, `settlement_updates`, `kyc_updates`, `low_stock_alerts`, `compliance_reminders`.
8. **Re-opt-in for WhatsApp**: If a customer re-enables WhatsApp in their app preferences after previously opting out via WA-native STOP, the `whatsapp_optouts.is_active` flag is set to `false` (re-opted-in).
9. **Preference Propagation**: Preference changes take effect immediately for new notifications. In-flight notifications already queued are not affected.
10. **Audit Log**: Preference changes are logged with previous and new values, timestamp, and source (user-initiated vs. unsubscribe-link vs. spam-report).

## API Endpoints

### GET /api/v1/customers/me/notification-preferences

Get notification preferences for the authenticated customer.

**Auth**: Bearer JWT - `customer`

**Response 200**
```json
{
  "success": true,
  "data": {
    "customer_id": "uuid-customer-1",
    "channels": {
      "push":      { "enabled": true,  "can_disable": true },
      "sms":       { "enabled": true,  "can_disable": true },
      "whatsapp":  { "enabled": true,  "can_disable": true },
      "email":     { "enabled": true,  "can_disable": true }
    },
    "categories": {
      "order_updates":     { "enabled": true,  "can_disable": false },
      "account_critical":  { "enabled": true,  "can_disable": false },
      "promotions":        { "enabled": true,  "can_disable": true },
      "refill_reminders":  { "enabled": true,  "can_disable": true },
      "offers":            { "enabled": false, "can_disable": true }
    },
    "whatsapp_optout_active": false,
    "updated_at": "2026-07-10T14:22:00Z"
  },
  "meta": {}
}
```

---

### PATCH /api/v1/customers/me/notification-preferences

Update notification preferences for the authenticated customer.

**Auth**: Bearer JWT - `customer`

**Request Body**
```json
{
  "channels": {
    "whatsapp": false,
    "email": true
  },
  "categories": {
    "promotions": false,
    "offers": false
  }
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "updated": true,
    "channels": {
      "push":      true,
      "sms":       true,
      "whatsapp":  false,
      "email":     true
    },
    "categories": {
      "order_updates":    true,
      "account_critical": true,
      "promotions":       false,
      "refill_reminders": true,
      "offers":           false
    },
    "updated_at": "2026-07-24T08:30:00Z"
  },
  "meta": {}
}
```

**Error Table**

| HTTP Code | Error Code | Condition |
|-----------|-----------|-----------|
| 422 | CANNOT_DISABLE_MANDATORY_CATEGORY | Attempt to disable order_updates or account_critical |

---

### GET /api/v1/pharmacy/notification-preferences

Get notification preferences for the authenticated pharmacy.

**Auth**: Bearer JWT - `pharmacy_owner`, `pharmacy_staff`

**Response 200**
```json
{
  "success": true,
  "data": {
    "pharmacy_id": "uuid-ph-1",
    "channels": {
      "push":      { "enabled": true,  "can_disable": true },
      "sms":       { "enabled": true,  "can_disable": true },
      "whatsapp":  { "enabled": true,  "can_disable": true },
      "email":     { "enabled": true,  "can_disable": true }
    },
    "categories": {
      "order_alerts":         { "enabled": true,  "can_disable": false },
      "settlement_updates":   { "enabled": true,  "can_disable": true },
      "kyc_updates":          { "enabled": true,  "can_disable": false },
      "low_stock_alerts":     { "enabled": true,  "can_disable": true },
      "compliance_reminders": { "enabled": true,  "can_disable": false }
    },
    "updated_at": "2026-07-05T09:00:00Z"
  },
  "meta": {}
}
```

---

### PATCH /api/v1/pharmacy/notification-preferences

Update pharmacy notification preferences.

**Auth**: Bearer JWT - `pharmacy_owner` only

**Request Body**
```json
{
  "channels": {
    "sms": false
  },
  "categories": {
    "low_stock_alerts": false
  }
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "updated": true,
    "updated_at": "2026-07-24T08:32:00Z"
  },
  "meta": {}
}
```

**Error Table**

| HTTP Code | Error Code | Condition |
|-----------|-----------|-----------|
| 403 | FORBIDDEN | pharmacy_staff cannot update preferences |
| 422 | CANNOT_DISABLE_MANDATORY_CATEGORY | Attempt to disable order_alerts or kyc_updates |

---

### POST /api/v1/notifications/unsubscribe

Public one-click unsubscribe from marketing emails.

**Auth**: None (public endpoint; token-based verification)

**Request Body**
```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9..."
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "unsubscribed": true,
    "message": "You have been unsubscribed from marketing emails. You will continue to receive order and account updates."
  },
  "meta": {}
}
```

**Error Table**

| HTTP Code | Error Code | Condition |
|-----------|-----------|-----------|
| 400 | INVALID_TOKEN | Token is malformed |
| 410 | TOKEN_EXPIRED | Token has expired (> 7 days) |
| 409 | ALREADY_UNSUBSCRIBED | Customer already unsubscribed |

---

## Data Models

### customer_notification_preferences

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| customer_id | UUID | FK ? customers |
| push_enabled | BOOLEAN | Default true |
| sms_enabled | BOOLEAN | Default true |
| whatsapp_enabled | BOOLEAN | Default true |
| email_enabled | BOOLEAN | Default true |
| cat_order_updates | BOOLEAN | Default true; non-disableable |
| cat_account_critical | BOOLEAN | Default true; non-disableable |
| cat_promotions | BOOLEAN | Default true |
| cat_refill_reminders | BOOLEAN | Default true |
| cat_offers | BOOLEAN | Default true |
| created_at | TIMESTAMPTZ | |
| updated_at | TIMESTAMPTZ | |

### pharmacy_notification_preferences

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| pharmacy_id | UUID | FK ? pharmacies |
| push_enabled | BOOLEAN | Default true |
| sms_enabled | BOOLEAN | Default true |
| whatsapp_enabled | BOOLEAN | Default true |
| email_enabled | BOOLEAN | Default true |
| cat_order_alerts | BOOLEAN | Default true; non-disableable |
| cat_settlement_updates | BOOLEAN | Default true |
| cat_kyc_updates | BOOLEAN | Default true; non-disableable |
| cat_low_stock_alerts | BOOLEAN | Default true |
| cat_compliance_reminders | BOOLEAN | Default true; non-disableable |
| updated_at | TIMESTAMPTZ | |

### notification_preference_audit

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| entity_type | VARCHAR(10) | CUSTOMER, PHARMACY |
| entity_id | UUID | |
| changed_by | UUID | Nullable (null for unsubscribe link) |
| change_source | VARCHAR(20) | USER, UNSUBSCRIBE_LINK, SPAM_REPORT, SYSTEM |
| old_values | JSONB | Previous preferences snapshot |
| new_values | JSONB | New preferences snapshot |
| changed_at | TIMESTAMPTZ | |

## Acceptance Criteria

1. **AC-001**: PATCH /customers/me/notification-preferences with `categories: { order_updates: false }` returns `422 CANNOT_DISABLE_MANDATORY_CATEGORY`.
2. **AC-002**: GET /customers/me/notification-preferences returns `can_disable: false` for `order_updates` and `account_critical` categories.
3. **AC-003**: POST /notifications/unsubscribe with a valid token disables the customer's `email.promotions` preference and adds to `email_unsubscribes`.
4. **AC-004**: POST /notifications/unsubscribe with an expired token (> 7 days) returns `410 TOKEN_EXPIRED`.
5. **AC-005**: PATCH /pharmacy/notification-preferences by a `pharmacy_staff` user returns `403 FORBIDDEN`.
6. **AC-006**: When a customer disables `whatsapp: false` in PATCH, subsequent notification sends check this preference and skip WhatsApp (while still sending push/SMS/email if enabled).
7. **AC-007**: After a WA "STOP" opt-out, the customer's `whatsapp_optout_active` field returns `true` in GET /preferences even if their `channels.whatsapp` is still `true` in preferences.
8. **AC-008**: Every preference change creates an audit log entry in `notification_preference_audit`.

## Dependencies

| Dependency | Type | Notes |
|-----------|------|-------|
| EPIC-017-STORY-001 | Consumer | Push preference check |
| EPIC-017-STORY-002 | Consumer | SMS preference check |
| EPIC-017-STORY-003 | Consumer | WhatsApp preference check |
| EPIC-017-STORY-004 | Consumer | Email preference check |
| EPIC-002 Auth | Auth | JWT validation |
| whatsapp_optouts table | STORY-003 | WA-native opt-out state |

## Notes

- The unsubscribe token is a standard HS256 JWT signed with the platform's JWT secret. It contains `sub: customer_id`, `email: customer_email`, `purpose: email_unsubscribe`, `exp: unix_timestamp`.
- Preferences are checked in real-time at dispatch (not cached) to ensure changes take effect immediately.
