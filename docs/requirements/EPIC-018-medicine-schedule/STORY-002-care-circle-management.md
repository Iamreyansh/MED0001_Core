# STORY-002: Care Circle Management - Family Member Scheduling

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-002 |
| **Epic** | EPIC-018 - Medicine Schedule |
| **Priority** | P1 |
| **Complexity** | M |
| **Status** | Draft |

---

## Overview

The Care Circle allows a customer to manage medicine schedules for their entire family under a single Namma MedMate account. A customer can add up to 10 family members (spouse, child, parent, sibling, or other), each represented by a `CareCircleMember` record. The account holder receives all reminders and refill alerts on behalf of the family. The customer's own profile is always a member (relationship: SELF) and cannot be removed. Each member gets their own schedule, adherence tracking, and today's dose view.

---

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| `customer` | Full read + write | Create, update, and remove family members |
| `admin_support` | Read-only | Support access for customer enquiries |
| `pharmacy_owner` | No access | Not applicable |

---

## Business Rules

1. **Self member is auto-created.** On a customer's first interaction with the schedule module, a `CareCircleMember` with `relationship = SELF`, `is_self = true`, and `name = customer.name` is created automatically if one doesn't already exist.
2. **Maximum 10 members.** A care circle can contain at most 10 members (including self). Attempting to add an 11th member returns `CARE_CIRCLE_LIMIT_REACHED`.
3. **Self member cannot be deleted.** `DELETE /care-circle/:member_id` returns 400 `CANNOT_DELETE_SELF` if `is_self = true`.
4. **Reminders go to account holder only.** Push notifications and SMS reminders are sent to the customer's registered phone number, not to family members. The reminder message includes the member's name (e.g., "Time for Dad's Metformin 500mg").
5. **Member deletion cascades to medicines.** Deleting a care circle member (non-self) soft-archives all their `ScheduleMedicine` records (`is_active = false`) and cancels all pending `ReminderSchedule` entries.
6. **Member identity is local.** `CareCircleMember` records are NOT independent customer accounts. They have a `name`, `age`, and `relationship` but no phone number, login, or separate identity in the system.
7. **Avatar customization.** Each member has an `avatar_emoji` (e.g., ??) and `avatar_color` (hex color) for visual differentiation in the UI. These are free-choice fields with no server-side validation beyond format.
8. **Today's adherence in list.** The `GET /care-circle` endpoint returns `today_adherence_pct` for each member - computed from that day's dose logs. This enables a family dashboard overview showing who has taken their medicines.

---

## API Endpoints

### 1. List Care Circle Members

```
GET /api/v1/schedule/care-circle
```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 60 req/min

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "members": [
      {
        "member_id": "uuid",
        "name": "Priya Sharma",
        "age": 34,
        "relationship": "SELF",
        "avatar_emoji": "??",
        "avatar_color": "#3B82F6",
        "is_self": true,
        "medicines_count": 3,
        "today_doses_total": 5,
        "today_doses_taken": 4,
        "today_adherence_pct": 80.0,
        "refill_alerts_count": 1
      },
      {
        "member_id": "uuid",
        "name": "Rajesh Sharma",
        "age": 68,
        "relationship": "PARENT",
        "avatar_emoji": "??",
        "avatar_color": "#10B981",
        "is_self": false,
        "medicines_count": 5,
        "today_doses_total": 8,
        "today_doses_taken": 5,
        "today_adherence_pct": 62.5,
        "refill_alerts_count": 2
      }
    ],
    "total_members": 2,
    "can_add_more": true
  }
}
```

---

### 2. Add Family Member

```
POST /api/v1/schedule/care-circle
```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 10 req/min

**Request Body (application/json):**

```json
{
  "name": "string max 100 - required",
  "age": "integer 0-120 - required",
  "relationship": "SPOUSE | CHILD | PARENT | SIBLING | OTHER - required",
  "avatar_emoji": "string single emoji - optional, default ??",
  "avatar_color": "string hex color - optional, default #6B7280"
}
```

**Success Response - 201 Created:**

```json
{
  "success": true,
  "data": {
    "member_id": "uuid",
    "name": "Rajesh Sharma",
    "age": 68,
    "relationship": "PARENT",
    "avatar_emoji": "??",
    "avatar_color": "#10B981",
    "is_self": false,
    "created_at": "2026-07-24T07:00:00Z"
  }
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `CARE_CIRCLE_LIMIT_REACHED` | Already has 10 members |
| 400 | `INVALID_AGE` | Age not in range 0-120 |
| 400 | `INVALID_AVATAR_COLOR` | `avatar_color` not a valid hex code |

---

### 3. Update Member Details

```
PATCH /api/v1/schedule/care-circle/:member_id
```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 30 req/min

**Request Body (application/json):**

```json
{
  "name": "string max 100 - optional",
  "age": "integer 0-120 - optional",
  "relationship": "SPOUSE | CHILD | PARENT | SIBLING | OTHER - optional",
  "avatar_emoji": "string single emoji - optional",
  "avatar_color": "string hex color - optional"
}
```

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "member_id": "uuid",
    "name": "Rajesh Sharma",
    "age": 69,
    "updated_at": "2026-07-24T08:00:00Z"
  }
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 403 | `MEMBER_ACCESS_DENIED` | Member does not belong to this customer |
| 404 | `MEMBER_NOT_FOUND` | Member ID not found |

---

### 4. Remove Family Member

```
DELETE /api/v1/schedule/care-circle/:member_id
```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 5 req/min

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "member_id": "uuid",
    "name": "Rajesh Sharma",
    "medicines_archived": 5,
    "reminders_cancelled": 28,
    "deleted_at": "2026-07-24T08:10:00Z"
  }
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `CANNOT_DELETE_SELF` | `is_self = true` - self member cannot be deleted |
| 403 | `MEMBER_ACCESS_DENIED` | Member does not belong to this customer |
| 404 | `MEMBER_NOT_FOUND` | Member ID not found |

---

### 5. Member Schedule Summary

```
GET /api/v1/schedule/care-circle/:member_id/summary
```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 60 req/min

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "member": {
      "member_id": "uuid",
      "name": "Rajesh Sharma",
      "age": 68,
      "relationship": "PARENT",
      "avatar_emoji": "??"
    },
    "today": {
      "total_doses": 8,
      "taken": 5,
      "skipped": 1,
      "missed": 0,
      "upcoming": 2,
      "adherence_pct": 62.5
    },
    "this_week_adherence_pct": 78.5,
    "refill_alerts": [
      {
        "medicine_name": "Amlodipine 5mg",
        "units_in_hand": 8,
        "approx_days_left": 4
      }
    ],
    "medicines": [
      {
        "medicine_id": "uuid",
        "medicine_name": "Amlodipine 5mg",
        "dose": "1 tablet",
        "form": "TABLET",
        "dose_slots": [{ "slot": "MORNING", "reminder_time": "08:00" }],
        "is_active": true
      }
    ]
  }
}
```

---

## Data Models

### CareCircleMember

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID | PK | Unique member ID |
| `customer_id` | UUID | FK ? Customer, NOT NULL | Owning customer account |
| `name` | VARCHAR(100) | NOT NULL | Member's full name |
| `age` | INTEGER | 0-120, NOT NULL | Age in years |
| `relationship` | ENUM | NOT NULL | SELF / SPOUSE / CHILD / PARENT / SIBLING / OTHER |
| `avatar_emoji` | VARCHAR(10) | NOT NULL, default '??' | Single emoji for avatar |
| `avatar_color` | VARCHAR(7) | NOT NULL, default '#6B7280' | Hex background color |
| `is_self` | BOOLEAN | NOT NULL, default false | True for the account holder |
| `created_at` | TIMESTAMPTZ | NOT NULL | Creation timestamp |
| `updated_at` | TIMESTAMPTZ | NOT NULL | Last update |

---

## Acceptance Criteria

- [ ] Given a customer's first call to `POST /schedule/medicines`, then a `CareCircleMember` with `relationship = SELF` is auto-created if one doesn't already exist.
- [ ] Given a care circle with 10 members, when `POST /care-circle` is called, then a 400 `CARE_CIRCLE_LIMIT_REACHED` error is returned.
- [ ] Given `DELETE /care-circle/:member_id` on the self member, then a 400 `CANNOT_DELETE_SELF` error is returned.
- [ ] Given `DELETE /care-circle/:member_id` on a member with 5 active medicines, then all 5 medicines are soft-deleted (`is_active = false`) and all their future reminders are cancelled.
- [ ] Given `GET /care-circle`, then each member row includes `today_adherence_pct` computed from today's dose logs.
- [ ] Given `PATCH /care-circle/:member_id` by a customer trying to update another customer's member, then a 403 `MEMBER_ACCESS_DENIED` is returned.
- [ ] Given `GET /care-circle/:member_id/summary`, then `refill_alerts` lists only medicines where `units_in_hand ? refill_remind_at_units` for that member.
- [ ] Given `POST /care-circle` with `avatar_color = "not_a_color"`, then a 400 `INVALID_AVATAR_COLOR` error is returned.

---

## Dependencies

- **EPIC-018 / STORY-001 (Medicine Schedule CRUD):** `ScheduleMedicine.member_id` references `CareCircleMember`.
- **EPIC-018 / STORY-003 (Reminder Engine):** Member deletion cancels reminders for all member's medicines.
- **EPIC-018 / STORY-004 (Adherence):** `today_adherence_pct` reads from `DoseLog` for the member.

---

## Notes

- The self member auto-creation should happen in the `POST /schedule/medicines` handler, not as a separate setup step. If the customer already has a self member, skip creation silently.
- Reminder messages for family members should include the member name for clarity, e.g., "Time for Dad's Amlodipine 5mg - take 1 tablet before breakfast."
- `can_add_more` in the list response is `true` when `total_members < 10`.
