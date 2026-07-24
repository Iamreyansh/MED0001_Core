# STORY-003: Dose Reminder Engine - Push Notification Scheduling & Delivery

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-003 |
| **Epic** | EPIC-018 - Medicine Schedule |
| **Priority** | P0 |
| **Complexity** | L |
| **Status** | Draft |

---

## Overview

This story defines the dose reminder notification system - the heart of the Medicine Schedule feature. The system pre-schedules push notifications for every active dose slot across all medicines for all care circle members. Reminders are delivered via FCM (Android) or APNs (iOS) at the exact `reminder_time` configured per dose slot. Customers can mark doses as TAKEN or SKIPPED from the notification or the app. The system auto-marks missed doses nightly and maintains a complete, immutable dose log for adherence tracking.

---

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| `customer` | Full access | View today's reminders, mark doses, view upcoming |
| `admin_support` | Read-only | View reminder delivery status for support tickets |
| System/Cron | Internal | Bulk schedule reminders, nightly missed-dose marking |

---

## Business Rules

1. **Reminder pre-scheduling for 7 days.** When a medicine is added or updated, the system calculates and creates `ReminderSchedule` records for the next 7 days. Every night at 00:00 IST, a cron job extends the window by 1 day (rolling 7-day window). Only active medicines with `units_in_hand > 0` (or supply tracking disabled) get reminders scheduled.
2. **Push notification delivery.** Reminders are sent via Firebase Cloud Messaging (FCM) for Android and APNs for iOS. The reminder message includes member name, medicine name, dose, and food instruction. The notification action opens the schedule screen.
3. **Missed dose auto-marking.** A nightly job at 02:00 IST checks all `DoseLog` records with `status = UPCOMING` and `scheduled_at < (now - 2 hours)`. These are updated to `status = MISSED`.
4. **Dose logs are immutable after 24 hours.** A customer can mark a dose TAKEN or SKIPPED only if `dose_date = today` or `dose_date = yesterday` (within 24 hours of the reminder). Attempting to mark an older dose returns `DOSE_LOG_LOCKED`.
5. **No reminders for inactive medicines.** When `is_active = false` (medicine deleted), all future `ReminderSchedule` entries for that medicine are cancelled (deleted). Past `DoseLog` records are preserved.
6. **Reminder delivery logging.** Each `ReminderSchedule` record tracks `sent_at`, `delivered_at` (confirmed by device), and `opened_at` (notification tapped). This enables delivery analytics and support debugging.
7. **Dose log deduplication.** There must be at most one `DoseLog` record per `(medicine_id, dose_date, slot)`. If a duplicate would be created (e.g., reminder fire for the same slot twice), the existing record is updated, not duplicated.
8. **CUSTOM slot handling.** For `slot = CUSTOM` dose slots, the `reminder_time` is the only schedule signal. The `DoseLog.slot` is stored as `CUSTOM`.

---

## API Endpoints

### 1. Bulk Schedule Reminders (Internal/System)

```
POST /api/v1/schedule/reminders/bulk-schedule
```

**Authentication:** Internal service token (not customer-accessible)
**Rate Limit:** 5 req/min

**Request Body (application/json):**

```json
{
  "customer_id": "UUID - required",
  "days_ahead": "integer 1-14 - optional, default 7"
}
```

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "customer_id": "uuid",
    "reminders_created": 28,
    "reminders_cancelled": 4,
    "scheduled_through": "2026-07-31",
    "processed_at": "2026-07-24T00:05:00Z"
  }
}
```

---

### 2. Today's Reminder Schedule

```
GET /api/v1/schedule/reminders/today
```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 120 req/min

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `member_id` | UUID | self | Care circle member |

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "member": { "member_id": "uuid", "name": "Priya Sharma" },
    "date": "2026-07-24",
    "dose_groups": [
      {
        "time_label": "8:00 AM",
        "reminder_time": "08:00",
        "doses": [
          {
            "dose_log_id": "uuid",
            "medicine_id": "uuid",
            "medicine_name": "Metformin 500mg",
            "strength": "500mg",
            "dose": "1 tablet",
            "form": "TABLET",
            "food_instruction": "AFTER",
            "slot": "MORNING",
            "status": "TAKEN",
            "taken_at": "2026-07-24T08:05:00Z"
          }
        ]
      },
      {
        "time_label": "9:00 PM",
        "reminder_time": "21:00",
        "doses": [
          {
            "dose_log_id": "uuid",
            "medicine_id": "uuid",
            "medicine_name": "Metformin 500mg",
            "dose": "1 tablet",
            "slot": "NIGHT",
            "status": "UPCOMING",
            "taken_at": null
          }
        ]
      }
    ],
    "summary": {
      "total": 5,
      "taken": 3,
      "skipped": 0,
      "missed": 0,
      "upcoming": 2
    }
  }
}
```

---

### 3. Mark Dose as Taken or Skipped

```
POST /api/v1/schedule/medicines/:medicine_id/doses/:date/:slot/mark
```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 60 req/min

**Path Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `medicine_id` | UUID | Medicine ID |
| `date` | string | Dose date in YYYY-MM-DD |
| `slot` | string | Slot name: MORNING, AFTERNOON, EVENING, NIGHT, CUSTOM |

**Request Body (application/json):**

```json
{
  "status": "TAKEN | SKIPPED - required",
  "taken_at": "ISO 8601 datetime - optional; defaults to current time if status = TAKEN"
}
```

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "dose_log_id": "uuid",
    "medicine_name": "Metformin 500mg",
    "date": "2026-07-24",
    "slot": "MORNING",
    "status": "TAKEN",
    "taken_at": "2026-07-24T08:05:00Z"
  }
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `DOSE_LOG_LOCKED` | `dose_date` is older than 24 hours |
| 400 | `INVALID_STATUS` | `status` not TAKEN or SKIPPED |
| 404 | `DOSE_LOG_NOT_FOUND` | No scheduled dose for this medicine/date/slot |
| 403 | `MEDICINE_ACCESS_DENIED` | Medicine belongs to another customer |

---

### 4. Upcoming Reminders (Next 24 Hours)

```
GET /api/v1/schedule/reminders/upcoming
```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 60 req/min

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `member_id` | UUID | self | Care circle member |
| `hours_ahead` | integer | `24` | Look-ahead window (max 48) |

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "upcoming_doses": [
      {
        "dose_log_id": "uuid",
        "medicine_name": "Metformin 500mg",
        "dose": "1 tablet",
        "slot": "NIGHT",
        "scheduled_at": "2026-07-24T21:00:00+05:30",
        "status": "UPCOMING",
        "hours_until": 8.75
      }
    ],
    "count": 3
  }
}
```

---

## Data Models

### DoseLog

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID | PK | Unique dose log entry |
| `medicine_id` | UUID | FK ? ScheduleMedicine, NOT NULL | Parent medicine |
| `customer_id` | UUID | FK ? Customer, NOT NULL | Account holder |
| `member_id` | UUID | FK ? CareCircleMember, NOT NULL | Member this dose is for |
| `dose_date` | DATE | NOT NULL | Date the dose is scheduled |
| `slot` | ENUM | NOT NULL | MORNING / AFTERNOON / EVENING / NIGHT / CUSTOM |
| `reminder_time` | TIME | NOT NULL | Scheduled reminder time (HH:MM) |
| `status` | ENUM | NOT NULL, default UPCOMING | UPCOMING / TAKEN / SKIPPED / MISSED |
| `taken_at` | TIMESTAMPTZ | nullable | Actual time dose was taken |
| `is_locked` | BOOLEAN | NOT NULL, default false | True after 24 hours - immutable |
| `created_at` | TIMESTAMPTZ | NOT NULL | Log entry creation |
| `updated_at` | TIMESTAMPTZ | NOT NULL | Last update |

**Unique constraint:** `(medicine_id, dose_date, slot)` - one log per dose per day.

### ReminderSchedule

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID | PK | Unique reminder record |
| `medicine_id` | UUID | FK ? ScheduleMedicine, NOT NULL | Associated medicine |
| `customer_id` | UUID | FK ? Customer, NOT NULL | Notification recipient |
| `dose_log_id` | UUID | FK ? DoseLog, NOT NULL | Linked dose log entry |
| `scheduled_at` | TIMESTAMPTZ | NOT NULL | Exact send time (UTC) |
| `channel` | ENUM | NOT NULL, default PUSH | PUSH / SMS |
| `status` | ENUM | NOT NULL, default SCHEDULED | SCHEDULED / SENT / DELIVERED / FAILED / CANCELLED |
| `notification_id` | VARCHAR(200) | nullable | FCM/APNs message ID |
| `sent_at` | TIMESTAMPTZ | nullable | Actual send timestamp |
| `delivered_at` | TIMESTAMPTZ | nullable | Device delivery confirmation |
| `opened_at` | TIMESTAMPTZ | nullable | Notification tap timestamp |
| `created_at` | TIMESTAMPTZ | NOT NULL | Record creation |

---

## Acceptance Criteria

- [ ] Given a new medicine with 2 dose slots (MORNING 08:00, NIGHT 21:00) is added on 2026-07-24, then 14 `DoseLog` records are created (2 per day - 7 days) and 14 `ReminderSchedule` entries are created.
- [ ] Given `POST /doses/:date/:slot/mark` with `status = TAKEN`, then the `DoseLog.status` changes to `TAKEN` and `taken_at` is set to the current time.
- [ ] Given `POST /doses/:date/:slot/mark` for a dose with `dose_date = 2026-07-22` (> 24 hours ago), then a 400 `DOSE_LOG_LOCKED` error is returned.
- [ ] Given a medicine is deleted (`is_active = false`), then all future `ReminderSchedule` records for that medicine with `status = SCHEDULED` are set to `status = CANCELLED`.
- [ ] Given a nightly auto-miss job runs at 02:00 IST, then all `DoseLog` records with `status = UPCOMING` and `reminder_time < (now - 2 hours)` for the previous day are updated to `status = MISSED`.
- [ ] Given `GET /reminders/today?member_id={id}`, then doses are grouped by `reminder_time` and each group shows all medicines scheduled at that time.
- [ ] Given two medicines both scheduled at 08:00 MORNING for the same member, then they appear in the same time group in the `GET /reminders/today` response.
- [ ] Given a `ReminderSchedule` sent successfully via FCM, then `status = SENT` and `sent_at` is recorded.

---

## Dependencies

- **EPIC-018 / STORY-001 (Medicine CRUD):** Medicine add/edit/delete triggers `bulk-schedule`.
- **EPIC-018 / STORY-002 (Care Circle):** Member deletion cancels all reminders.
- **EPIC-018 / STORY-004 (Adherence):** `DoseLog` is the source of truth for adherence computation.
- **EPIC-010 (Notifications):** FCM/APNs push notification delivery service.

---

## Notes

- The `bulk-schedule` endpoint is called internally after any medicine mutation. It is idempotent: re-running for the same time window should not create duplicate `DoseLog` or `ReminderSchedule` records - it should update existing ones if the schedule has changed.
- The rolling 7-day window is maintained by a nightly cron job (00:00 IST) that calls `bulk-schedule` for each customer with active medicines.
- FCM delivery receipts are handled via a webhook from the FCM server. When a delivery receipt arrives, the `ReminderSchedule.delivered_at` field is updated.
- SMS fallback for push notification failures should be configurable per customer in their notification preferences (out of scope for this story).
