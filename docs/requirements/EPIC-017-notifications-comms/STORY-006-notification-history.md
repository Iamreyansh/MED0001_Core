# STORY-006: Notification History

| Field | Value |
|-------|-------|
| Story ID | EPIC-017-STORY-006 |
| Epic | EPIC-017 Notifications and Communications |
| Title | Notification History |
| Priority | P1 |
| Status | In Development |
| Role | customer, admin_super, admin_operations |
| Last Updated | 2026-07-24 |

## Overview

The Notification History story provides an in-app notification inbox for customers, surfacing order updates, promotional messages, refill reminders, and system alerts as a persistent bell-icon notification center. Customers can mark notifications as read, delete promotional ones, and see an unread badge count. The admin notification history endpoint provides a full cross-channel audit view of all notifications sent on the platform, filterable by channel, status, and date range, with CSV export support.

## User Roles

| Role | Access |
|------|--------|
| customer | View, read, and delete their own in-app notifications |
| admin_super | View admin notification history across all channels |
| admin_operations | View admin notification history |

## Business Rules

1. **In-App Retention**: In-app notifications are stored in the database for 30 days. After 30 days, they are soft-deleted (archived, not queryable by the customer). Order update notifications are retained for 90 days.
2. **Unread Badge Count**: The badge count on the notification bell icon is driven by the GET /count endpoint, which returns the count of in-app notifications with `is_read: false`.
3. **Permanent Order Notifications**: In-app notifications of type `ORDER_UPDATE` are read-only for customers (cannot be deleted). This ensures customers always have access to their order history trail.
4. **Auto-Read on Open**: When a customer opens a notification's deep-link action_url (i.e., taps a notification), the system marks it as read. The POST /read endpoint is also available for explicit mark-as-read.
5. **Mark All Read**: There is no dedicated mark-all-read endpoint; it is implemented via a PUT request with `{ mark_all_read: true }` on the notifications endpoint (described in the API section below).
6. **Notification Types**: `ORDER_UPDATE` (status changes), `PROMO` (discount/offer messages), `REFILL_REMINDER` (medicine schedule reminders), `SYSTEM` (platform announcements, maintenance). Type determines retention and deletion permission.
7. **Auto-Generation**: In-app notifications are auto-created as side effects of system events (order status change triggers an ORDER_UPDATE notification, etc.). There is no manual customer-facing create endpoint.
8. **Admin History Scope**: The admin notification history view covers all channels (push, SMS, WhatsApp, email) and is read from the aggregated `notification_dispatch_log` view. It is paginated and filterable.
9. **Soft Delete**: Deleting a `PROMO` or `SYSTEM` notification sets `is_deleted: true`; it no longer appears in GET /notifications but is retained in the database for 30 days before hard deletion.
10. **Dot Disappears at Zero**: The bell-icon dot (unread indicator) disappears when `count = 0`. The count endpoint returns 0 (not null) when there are no unread notifications.

## API Endpoints

### GET /api/v1/customers/me/notifications

Retrieve the in-app notification inbox for the authenticated customer.

**Auth**: Bearer JWT - `customer`

**Query Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| unread_only | boolean | No | Return only unread notifications |
| type | string | No | ORDER_UPDATE, PROMO, REFILL_REMINDER, SYSTEM |
| page | integer | No | Default 1 |
| limit | integer | No | Default 20 |

**Response 200**
```json
{
  "success": true,
  "data": {
    "notifications": [
      {
        "id": "uuid-notif-1",
        "type": "ORDER_UPDATE",
        "title": "Your order is out for delivery!",
        "body": "Ramesh Kumar is on the way. ETA: 12 minutes.",
        "action_url": "nmmedmate://order/uuid-order-1",
        "is_read": false,
        "can_delete": false,
        "created_at": "2026-07-24T08:14:22Z"
      },
      {
        "id": "uuid-notif-2",
        "type": "PROMO",
        "title": "20% off vitamins this weekend!",
        "body": "Use code VITA20 before Sunday midnight.",
        "action_url": "nmmedmate://offers/VITA20",
        "is_read": true,
        "can_delete": true,
        "created_at": "2026-07-22T10:00:00Z"
      }
    ]
  },
  "meta": { "page": 1, "limit": 20, "total": 14 }
}
```

---

### POST /api/v1/customers/me/notifications/:id/read

Mark a single notification as read.

**Auth**: Bearer JWT - `customer`

**Path Parameters**

| Parameter | Type | Description |
|-----------|------|-------------|
| id | UUID | Notification ID |

**Response 200**
```json
{
  "success": true,
  "data": {
    "id": "uuid-notif-1",
    "is_read": true,
    "read_at": "2026-07-24T08:28:00Z"
  },
  "meta": {}
}
```

**Error Table**

| HTTP Code | Error Code | Condition |
|-----------|-----------|-----------|
| 404 | NOTIFICATION_NOT_FOUND | ID does not exist or belongs to another user |

---

### PUT /api/v1/customers/me/notifications

Mark all notifications as read (bulk action).

**Auth**: Bearer JWT - `customer`

**Request Body**
```json
{
  "mark_all_read": true
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "marked_read_count": 8,
    "updated_at": "2026-07-24T08:30:00Z"
  },
  "meta": {}
}
```

---

### DELETE /api/v1/customers/me/notifications/:id

Soft-delete a notification (PROMO or SYSTEM types only).

**Auth**: Bearer JWT - `customer`

**Path Parameters**

| Parameter | Type | Description |
|-----------|------|-------------|
| id | UUID | Notification ID |

**Response 200**
```json
{
  "success": true,
  "data": {
    "deleted": true
  },
  "meta": {}
}
```

**Error Table**

| HTTP Code | Error Code | Condition |
|-----------|-----------|-----------|
| 404 | NOTIFICATION_NOT_FOUND | ID not found or belongs to another user |
| 422 | CANNOT_DELETE_ORDER_UPDATE | ORDER_UPDATE notifications cannot be deleted |

---

### GET /api/v1/customers/me/notifications/count

Get unread notification count for badge display.

**Auth**: Bearer JWT - `customer`

**Response 200**
```json
{
  "success": true,
  "data": {
    "unread_count": 3
  },
  "meta": {}
}
```

---

### GET /api/v1/admin/notifications/history

Admin view of all notifications sent across all channels.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Query Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| channel | string | No | PUSH, SMS, WHATSAPP, EMAIL |
| status | string | No | Channel-specific statuses |
| recipient_type | string | No | CUSTOMER, PHARMACY_STAFF, RIDER |
| date_from | string | No | ISO 8601 |
| date_to | string | No | ISO 8601 |
| export | string | No | `csv` |
| page | integer | No | Default 1 |
| limit | integer | No | Default 20 |

**Response 200**
```json
{
  "success": true,
  "data": {
    "history": [
      {
        "dispatch_id": "uuid-dispatch-1",
        "recipient_id": "uuid-customer-1",
        "recipient_name": "Ravi Kumar",
        "recipient_type": "CUSTOMER",
        "channel": "PUSH",
        "type": "ORDER_UPDATE",
        "title": "Your order is out for delivery!",
        "status": "DELIVERED",
        "sent_at": "2026-07-24T08:14:22Z",
        "delivered_at": "2026-07-24T08:14:24Z"
      },
      {
        "dispatch_id": "uuid-dispatch-2",
        "recipient_id": "uuid-customer-1",
        "recipient_name": "Ravi Kumar",
        "recipient_type": "CUSTOMER",
        "channel": "WHATSAPP",
        "type": "ORDER_UPDATE",
        "title": "ORDER_CONFIRMED template",
        "status": "READ",
        "sent_at": "2026-07-24T08:14:23Z",
        "delivered_at": "2026-07-24T08:14:30Z"
      }
    ],
    "export_url": null
  },
  "meta": { "page": 1, "limit": 20, "total": 48200 }
}
```

---

## Data Models

### customer_in_app_notifications

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| customer_id | UUID | FK ? customers |
| type | VARCHAR(20) | ORDER_UPDATE, PROMO, REFILL_REMINDER, SYSTEM |
| title | TEXT | |
| body | TEXT | |
| action_url | TEXT | Deep-link URL |
| is_read | BOOLEAN | Default false |
| is_deleted | BOOLEAN | Default false (soft delete) |
| read_at | TIMESTAMPTZ | Nullable |
| expires_at | TIMESTAMPTZ | 30 days; 90 days for ORDER_UPDATE |
| created_at | TIMESTAMPTZ | |

### notification_dispatch_log (view / aggregate)

| Column | Type | Notes |
|--------|------|-------|
| dispatch_id | UUID | Sourced from per-channel log IDs |
| recipient_id | UUID | Customer/pharmacy/rider ID |
| recipient_type | VARCHAR(15) | |
| channel | VARCHAR(10) | PUSH, SMS, WHATSAPP, EMAIL |
| type | VARCHAR(20) | Notification event type |
| title | TEXT | Subject or push title |
| status | VARCHAR(15) | Channel-specific delivery status |
| sent_at | TIMESTAMPTZ | |
| delivered_at | TIMESTAMPTZ | Nullable |

## Acceptance Criteria

1. **AC-001**: GET /notifications?unread_only=true returns only notifications with `is_read: false` for the authenticated customer.
2. **AC-002**: GET /notifications/count returns `unread_count: 0` (not null) when no unread notifications exist; bell-dot should be hidden.
3. **AC-003**: DELETE /notifications/:id for an ORDER_UPDATE type notification returns `422 CANNOT_DELETE_ORDER_UPDATE`.
4. **AC-004**: POST /notifications/:id/read sets `is_read: true` and `read_at` to current timestamp.
5. **AC-005**: PUT /notifications with `{ mark_all_read: true }` marks all unread notifications as read and returns the count of notifications updated.
6. **AC-006**: GET /notifications after 30 days does not return PROMO notifications older than 30 days (TTL enforcement).
7. **AC-007**: GET /admin/notifications/history with `?channel=SMS&status=FAILED` returns only failed SMS entries.
8. **AC-008**: GET /admin/notifications/history with `?export=csv` returns a pre-signed S3 download URL.
9. **AC-009**: In-app notifications are auto-created on order status change events; no manual creation endpoint needed or exposed.

## Dependencies

| Dependency | Type | Notes |
|-----------|------|-------|
| EPIC-001 Order Management | Event source | ORDER_UPDATE notifications |
| EPIC-017-STORY-001 | Data source | Push delivery log |
| EPIC-017-STORY-002 | Data source | SMS delivery log |
| EPIC-017-STORY-003 | Data source | WhatsApp delivery log |
| EPIC-017-STORY-004 | Data source | Email delivery log |
| AWS S3 | Storage | Admin history CSV export |
| TTL cleanup job | Infrastructure | Deletes expired in-app notifications |

## Notes

- The `notification_dispatch_log` is a database view joining all four channel-specific log tables (push, SMS, WhatsApp, email) for the admin history endpoint. It is not a physical table.
- In-app notification bell should poll GET /count every 60 seconds (or use WebSocket subscription if platform adopts real-time connections in a future sprint).
- Future: Add notification categories to the inbox (ORDER, PROMOTIONS, REFILLS tabs) for better UX.
