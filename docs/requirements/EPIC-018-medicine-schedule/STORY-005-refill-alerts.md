# STORY-005: Refill Alerts - Supply Tracking and Reorder

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-005 |
| **Epic** | EPIC-018 - Medicine Schedule |
| **Priority** | P1 |
| **Complexity** | M |
| **Status** | Draft |

---

## Overview

This story covers the supply tracking and refill alert system for the Medicine Schedule. When a customer adds `refill_units_in_hand` to a medicine, the system tracks remaining supply by decrementing units daily based on scheduled doses. When supply falls to or below `refill_remind_at_units`, the system fires a push notification and surfaces a refill alert in the app. Customers can record a manual refill, order online via the customer app, or share their schedule summary as a read-only link (useful for caregivers or doctors).

---

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| `customer` | Full read + write | View alerts, record refills, order online |
| `admin_support` | Read-only | Support queries |
| `pharmacy_owner` | No access | Not applicable |

---

## Business Rules

1. **Daily supply decrement.** Each active medicine with `units_in_hand > 0` and supply tracking enabled has its `units_in_hand` decremented by `total_daily_doses` (count of active `dose_slots`) every night at 00:30 IST. Supply decrement also occurs in real time when a dose is marked TAKEN (STORY-003).
2. **Refill alert trigger.** A refill alert is triggered when `units_in_hand ? refill_remind_at_units` AND `refill_remind_at_units > 0`. The alert is a push notification AND a persistent in-app banner. If `refill_remind_at_units = 0`, supply tracking is disabled for that medicine and no refill alerts are fired.
3. **Alert push notification cadence.** Once a refill alert fires, a reminder push notification is sent once per day (not every hour or every minute). Repeated daily alerts stop when `units_in_hand > refill_remind_at_units` (after a refill is recorded) or when the medicine is removed.
4. **`approx_days_left` computation.** `approx_days_left = floor(units_in_hand / doses_per_day)` where `doses_per_day = count of active dose_slots`. If `doses_per_day = 0`, the field returns `null`.
5. **Manual refill adds units.** `POST /medicines/:id/refill` adds `units_added` to `units_in_hand`. It does not set a fixed value - it is always additive. The refill is logged with a timestamp for history.
6. **Order online intent.** `POST /medicines/:id/refill/order-online` does not create an order directly. It returns a deep link or redirect URI that opens the customer app's home screen with the medicine pre-searched. If `master_medicine_id` is set, the search is pre-populated with the exact product.
7. **Share link validity.** The shareable schedule summary link is valid for 30 days and requires no authentication to view. It is a read-only JSON/HTML rendering of the member's current schedule (medicine names, doses, times) - no dose log or adherence data is included.
8. **Refill alert list scope.** `GET /refill-alerts` returns medicines where `units_in_hand ? refill_remind_at_units` AND `refill_remind_at_units > 0` for the specified member. It does NOT show all medicines - only those currently in alert state.

---

## API Endpoints

### 1. Refill Alerts List

```
GET /api/v1/schedule/refill-alerts
```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 60 req/min

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
    "refill_alerts_count": 2,
    "alerts": [
      {
        "medicine_id": "uuid",
        "medicine_name": "Metformin 500mg",
        "strength": "500mg",
        "form": "TABLET",
        "units_in_hand": 8,
        "refill_remind_at_units": 10,
        "doses_per_day": 2,
        "approx_days_left": 4,
        "master_medicine_id": "uuid",
        "can_order_online": true,
        "alert_level": "CRITICAL"
      },
      {
        "medicine_id": "uuid",
        "medicine_name": "Vitamin D3 60K IU",
        "units_in_hand": 5,
        "refill_remind_at_units": 6,
        "doses_per_day": 1,
        "approx_days_left": 5,
        "master_medicine_id": null,
        "can_order_online": false,
        "alert_level": "WARNING"
      }
    ]
  }
}
```

> `alert_level`: `CRITICAL` when `approx_days_left ? 3`, `WARNING` otherwise.

---

### 2. Record Manual Refill

```
POST /api/v1/schedule/medicines/:medicine_id/refill
```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 20 req/min

**Request Body (application/json):**

```json
{
  "units_added": "integer > 0 - required",
  "refill_date": "date YYYY-MM-DD - optional; defaults to today"
}
```

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "medicine_id": "uuid",
    "medicine_name": "Metformin 500mg",
    "units_added": 60,
    "previous_units": 8,
    "new_units_in_hand": 68,
    "approx_days_left": 34,
    "refill_alert_cleared": true,
    "refill_date": "2026-07-24"
  }
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `INVALID_UNITS` | `units_added ? 0` |
| 403 | `MEDICINE_ACCESS_DENIED` | Medicine belongs to another customer |
| 404 | `MEDICINE_NOT_FOUND` | Medicine not found |

---

### 3. Order Online (Refill Intent)

```
POST /api/v1/schedule/medicines/:medicine_id/refill/order-online
```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 20 req/min

**Request Body:** None.

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "medicine_name": "Metformin 500mg",
    "master_medicine_id": "uuid",
    "redirect_url": "medmate://search?query=Metformin+500mg&master_id=uuid",
    "web_redirect_url": "https://app.medmate.in/search?query=Metformin+500mg&master_id=uuid"
  }
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 404 | `MEDICINE_NOT_FOUND` | Medicine not found |

---

### 4. Generate Shareable Schedule Link

```
GET /api/v1/schedule/refill-alerts/share
```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 5 req/min

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `member_id` | UUID | self | Care circle member to share schedule for |

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "share_link": "https://app.medmate.in/schedule/share/abc123XYZ",
    "token": "abc123XYZ",
    "expires_at": "2026-08-23T07:00:00Z",
    "member_name": "Priya Sharma",
    "medicines_count": 3
  }
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 403 | `MEMBER_ACCESS_DENIED` | Member does not belong to this customer |

---

### 5. View Shared Schedule (Public, No Auth)

```
GET /api/v1/schedule/share/:token
```

**Authentication:** None (public endpoint)
**Rate Limit:** 30 req/min per token

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "member_name": "Priya Sharma",
    "share_expires_at": "2026-08-23T07:00:00Z",
    "medicines": [
      {
        "medicine_name": "Metformin 500mg",
        "dose": "1 tablet",
        "form": "TABLET",
        "food_instruction": "AFTER",
        "dose_slots": [
          { "slot": "MORNING", "time": "08:00 AM" },
          { "slot": "NIGHT", "time": "09:00 PM" }
        ],
        "condition_name": "Type 2 Diabetes",
        "prescribed_by": "Dr. Anil Sharma"
      }
    ]
  }
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 404 | `SHARE_LINK_NOT_FOUND` | Token does not exist |
| 410 | `SHARE_LINK_EXPIRED` | Token has expired (> 30 days) |

---

## Data Models

> Refill alert state is derived from `ScheduleMedicine.units_in_hand` and `ScheduleMedicine.refill_remind_at_units`. No separate alert table is needed.

### RefillLog (Audit Trail)

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID | PK | Unique refill event ID |
| `medicine_id` | UUID | FK ? ScheduleMedicine, NOT NULL | Medicine restocked |
| `customer_id` | UUID | FK ? Customer, NOT NULL | Account holder |
| `units_added` | INTEGER | > 0, NOT NULL | Units added to hand |
| `units_before` | INTEGER | NOT NULL | Supply before refill |
| `units_after` | INTEGER | NOT NULL | Supply after refill |
| `refill_date` | DATE | NOT NULL | Date of refill (customer-reported) |
| `created_at` | TIMESTAMPTZ | NOT NULL | Log entry creation time |

### ScheduleShareToken

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID | PK | Token record ID |
| `token` | VARCHAR(50) | NOT NULL, UNIQUE | URL-safe share token |
| `customer_id` | UUID | FK ? Customer, NOT NULL | Owning customer |
| `member_id` | UUID | FK ? CareCircleMember, NOT NULL | Member whose schedule is shared |
| `expires_at` | TIMESTAMPTZ | NOT NULL | 30 days from creation |
| `created_at` | TIMESTAMPTZ | NOT NULL | Token creation time |

---

## Acceptance Criteria

- [ ] Given a medicine with `units_in_hand = 8` and `refill_remind_at_units = 10`, when `GET /refill-alerts` is called, then that medicine appears in the alerts list with correct `approx_days_left`.
- [ ] Given `POST /medicines/:id/refill` with `units_added = 60`, then `units_in_hand` increases from 8 to 68 and `refill_alert_cleared = true` is returned.
- [ ] Given `POST /medicines/:id/refill` with `units_added = 0`, then a 400 `INVALID_UNITS` error is returned.
- [ ] Given `POST /medicines/:id/refill/order-online` for a medicine with `master_medicine_id` set, then a valid deep link URL containing the `master_id` is returned.
- [ ] Given `GET /refill-alerts/share`, then a share link with a 30-day expiry is returned and accessible at `GET /schedule/share/:token` without authentication.
- [ ] Given `GET /schedule/share/:token` after 30 days, then a 410 `SHARE_LINK_EXPIRED` error is returned.
- [ ] Given a medicine with `refill_remind_at_units = 0` (tracking disabled), then it does NOT appear in `GET /refill-alerts` regardless of `units_in_hand` value.
- [ ] Given the nightly supply decrement job runs on a medicine with 2 dose slots and `units_in_hand = 5`, then `units_in_hand` decrements to 3 the next morning.

---

## Dependencies

- **EPIC-018 / STORY-001 (Medicine CRUD):** `units_in_hand` and `refill_remind_at_units` are set and managed in STORY-001.
- **EPIC-018 / STORY-003 (Dose Reminder Engine):** Dose-taken marking also decrements `units_in_hand` in real time.
- **EPIC-002 (Customer App / Orders):** The order-online redirect deep-links into the customer app's search flow.
- **EPIC-010 (Notifications):** Daily refill push alert sent via notification service.

---

## Notes

- The nightly supply decrement job (00:30 IST) should be idempotent. It should record each decrement event in `RefillLog` with `units_added = -(total_daily_doses)` for a complete audit trail of supply changes.
- The share link public endpoint should not expose any medical history, dose logs, or adherence data - only the current active schedule (medicine names, doses, timings, and prescribing doctor).
- Share tokens should be cryptographically random (not sequential) to prevent enumeration attacks. Use a UUID v4 or a 32-character URL-safe base64 token.
- `can_order_online = true` in the refill alert when `master_medicine_id` is set. If `master_medicine_id = null`, the medicine was entered manually and cannot be searched in the catalog for online ordering (set `can_order_online = false`).
