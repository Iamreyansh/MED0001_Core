# STORY-001: Push Notification Service

| Field | Value |
|-------|-------|
| Story ID | EPIC-017-STORY-001 |
| Epic | EPIC-017 Notifications and Communications |
| Title | Push Notification Service |
| Priority | P1 |
| Status | In Development |
| Role | Internal service + admin_super |
| Last Updated | 2026-07-24 |

## Overview

The Push Notification Service manages Firebase Cloud Messaging (FCM) device token registration for all app users (customers, pharmacy staff, and riders) and provides a send endpoint used internally by other services. For iOS, notifications are delivered via Apple Push Notification service (APNs) through Firebase. The service supports targeted delivery to individual recipients or segments, HIGH-priority delivery for order-critical notifications, and admin-initiated broadcast to entire audiences. All push delivery events are logged for analytics and debugging.

## User Roles

| Role | Access |
|------|--------|
| customer | Register/unregister own device token |
| pharmacy_staff | Register/unregister own device token |
| rider | Register/unregister own device token |
| admin_super | Send broadcasts; view delivery logs |
| Internal services | Call push/send endpoint (service-to-service auth) |

## Business Rules

1. **Multi-Device Support**: A single user (customer, pharmacy staff, or rider) may register multiple device tokens (one per device). When sending a push to a user, all active tokens for that user are targeted.
2. **Token Refresh**: Device tokens are updated on every app open (app-level `onTokenRefresh` callback calls POST /device-token). If the new token differs from the stored one, the old token is replaced for that device_id.
3. **Token Cleanup**: Tokens that receive an `INVALID_REGISTRATION` or `NOT_REGISTERED` error from FCM are automatically deleted from the database and will not be retried.
4. **Priority Levels**: `HIGH` priority bypasses FCM batching and delivers immediately; used for order status changes. `NORMAL` priority is batched; used for promotions and non-urgent alerts.
5. **Silent Push**: Data-only pushes (no `title`/`body`) are used for background data sync (e.g., refreshing order status in the app background). These are sent with `content_available: true` for iOS.
6. **Broadcast Audience Resolution**: When a broadcast is sent to `ALL_CUSTOMERS`, the platform resolves the list of all active device tokens for customers at send time (not at schedule time). Scheduled broadcasts store the audience type and resolve at execution time.
7. **Payload Limit**: FCM push payloads must not exceed 4 KB. The send endpoint validates payload size before attempting delivery.
8. **Delivery Logging**: Every push attempt is logged with status (SENT, DELIVERED, FAILED). FCM delivery receipts (when available via FCM Data API) update the log record. Open events tracked via `action_url` deep link click.
9. **No Retry by Platform**: FCM handles TTL-based delivery internally. The platform does not implement its own retry for FAILED pushes (to avoid duplicate delivery).
10. **DND Exemption**: Push notifications are not subject to TRAI DND regulations (DND applies to SMS/voice only). Customers can opt out of push via notification preferences (STORY-005).

## API Endpoints

### POST /api/v1/notifications/push/send

Internal endpoint to send a push notification to one or more recipients.

**Auth**: Service-to-service JWT (internal only; not customer-facing)

**Request Body**
```json
{
  "recipient_type": "CUSTOMER",
  "recipient_ids": ["uuid-customer-1", "uuid-customer-2"],
  "title": "Your order is out for delivery!",
  "body": "Ramesh is on the way. ETA: 12 minutes.",
  "data": {
    "order_id": "uuid-order-1",
    "screen": "ORDER_TRACKING"
  },
  "image_url": "https://cdn.nammamedmate.in/icons/delivery.png",
  "action_url": "nmmedmate://order/uuid-order-1",
  "priority": "HIGH"
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "sent": 2,
    "failed": 0,
    "tokens_targeted": 3,
    "log_ids": ["uuid-log-1", "uuid-log-2", "uuid-log-3"]
  },
  "meta": {}
}
```

**Error Table**

| HTTP Code | Error Code | Condition |
|-----------|-----------|-----------|
| 400 | PAYLOAD_TOO_LARGE | Serialized payload exceeds 4 KB |
| 400 | MISSING_RECIPIENT | recipient_ids empty and no segment_id |
| 422 | INVALID_RECIPIENT_TYPE | recipient_type not in allowed set |

---

### POST /api/v1/customers/me/device-token

Register or update FCM device token for the authenticated customer.

**Auth**: Bearer JWT - `customer`

**Request Body**
```json
{
  "token": "fcm-token-string-abc123",
  "platform": "ANDROID",
  "device_id": "device-uuid-from-app"
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "registered": true,
    "device_id": "device-uuid-from-app",
    "platform": "ANDROID"
  },
  "meta": {}
}
```

**Error Table**

| HTTP Code | Error Code | Condition |
|-----------|-----------|-----------|
| 400 | INVALID_PLATFORM | Platform not IOS or ANDROID |
| 400 | MISSING_TOKEN | token field is empty |

---

### DELETE /api/v1/customers/me/device-token

Unregister device token on logout.

**Auth**: Bearer JWT - `customer`

**Request Body**
```json
{
  "device_id": "device-uuid-from-app"
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "unregistered": true
  },
  "meta": {}
}
```

**Note**: The same token registration/unregistration pattern applies for pharmacy_staff (`/api/v1/pharmacy/me/device-token`) and rider (`/api/v1/rider/me/device-token`) endpoints - identical request/response shape.

---

### GET /api/v1/admin/notifications/push/logs

Retrieve push notification delivery logs.

**Auth**: Bearer JWT - `admin_super`

**Query Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| recipient_type | string | No | CUSTOMER, PHARMACY_STAFF, RIDER |
| status | string | No | SENT, DELIVERED, FAILED |
| date_from | string | No | ISO 8601 |
| date_to | string | No | ISO 8601 |
| page | integer | No | Default 1 |
| limit | integer | No | Default 20 |

**Response 200**
```json
{
  "success": true,
  "data": {
    "logs": [
      {
        "log_id": "uuid-log-1",
        "recipient_type": "CUSTOMER",
        "recipient_id": "uuid-customer-1",
        "recipient_name": "Ravi Kumar",
        "title": "Your order is out for delivery!",
        "priority": "HIGH",
        "status": "DELIVERED",
        "sent_at": "2026-07-24T08:14:22Z",
        "delivered_at": "2026-07-24T08:14:24Z",
        "opened_at": "2026-07-24T08:16:01Z",
        "error_message": null
      }
    ]
  },
  "meta": { "page": 1, "limit": 20, "total": 4820 }
}
```

---

### POST /api/v1/admin/notifications/broadcast

Send a broadcast push notification to all customers, pharmacies, or riders.

**Auth**: Bearer JWT - `admin_super`

**Request Body**
```json
{
  "audience": "ALL_CUSTOMERS",
  "title": "Namma MedMate is now live in Whitefield!",
  "body": "Get medicines delivered in 45 minutes. Try now!",
  "data": { "screen": "HOME" },
  "schedule_at": null
}
```

**Response 202**
```json
{
  "success": true,
  "data": {
    "broadcast_id": "uuid-broadcast-1",
    "audience": "ALL_CUSTOMERS",
    "status": "QUEUED",
    "estimated_recipients": 4820,
    "scheduled_at": null,
    "queued_at": "2026-07-24T01:40:00Z"
  },
  "meta": {}
}
```

**Error Table**

| HTTP Code | Error Code | Condition |
|-----------|-----------|-----------|
| 400 | INVALID_AUDIENCE | audience not in allowed set |
| 403 | FORBIDDEN | Non-admin_super role |
| 422 | PAYLOAD_TOO_LARGE | Push payload > 4 KB |

---

## Data Models

### device_tokens

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| user_id | UUID | FK ? customers / pharmacy_staff / riders |
| user_type | VARCHAR(15) | CUSTOMER, PHARMACY_STAFF, RIDER |
| token | TEXT | FCM token |
| platform | VARCHAR(10) | IOS, ANDROID |
| device_id | VARCHAR(200) | App-generated device identifier |
| is_active | BOOLEAN | False when FCM returns INVALID/NOT_REGISTERED |
| registered_at | TIMESTAMPTZ | |
| last_refreshed_at | TIMESTAMPTZ | Updated on each app open |

### push_notification_logs

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| broadcast_id | UUID | FK ? push_broadcasts (nullable for targeted) |
| recipient_user_id | UUID | |
| recipient_type | VARCHAR(15) | |
| device_token_id | UUID | FK ? device_tokens |
| title | TEXT | |
| body | TEXT | |
| priority | VARCHAR(6) | HIGH, NORMAL |
| fcm_message_id | VARCHAR(200) | FCM response message_id |
| status | VARCHAR(15) | SENT, DELIVERED, FAILED |
| sent_at | TIMESTAMPTZ | |
| delivered_at | TIMESTAMPTZ | Nullable |
| opened_at | TIMESTAMPTZ | Nullable |
| error_message | TEXT | Nullable |

## Acceptance Criteria

1. **AC-001**: POST /device-token with a new token for an existing device_id replaces the old token; the old token is deactivated.
2. **AC-002**: DELETE /device-token marks the device token as inactive; subsequent pushes to the same user do not target this token.
3. **AC-003**: POST /push/send with payload > 4 KB returns `400 PAYLOAD_TOO_LARGE` before calling FCM.
4. **AC-004**: When FCM returns `NOT_REGISTERED` for a token, the token's `is_active` is set to `false` and the log entry status is `FAILED`.
5. **AC-005**: POST /broadcast with `schedule_at: null` immediately enqueues the broadcast and returns `status: QUEUED`.
6. **AC-006**: GET /push/logs returns `opened_at` timestamp when the customer has clicked the notification's action_url deep link.
7. **AC-007**: POST /push/send targeting a user with 3 device tokens creates 3 separate log entries (one per token).
8. **AC-008**: Broadcast to ALL_CUSTOMERS resolves recipient list at execution time, not at schedule time.

## Dependencies

| Dependency | Type | Notes |
|-----------|------|-------|
| Firebase Cloud Messaging | External | Push delivery for Android + iOS |
| EPIC-001 Order Management | Event source | Order status change triggers |
| EPIC-017-STORY-005 | Gate | Preference check before sending |
| Background job queue | Infrastructure | Broadcast fan-out processing |

## Notes

- Rider and pharmacy_staff device token endpoints mirror the customer endpoint pattern but under `/api/v1/rider/me/device-token` and `/api/v1/pharmacy/me/device-token` paths.
- Apple APNs integration is handled transparently by Firebase; no direct APNs credentials needed in the platform.
- Data-only (silent) pushes for background sync must not be counted as "promotional" in analytics.
