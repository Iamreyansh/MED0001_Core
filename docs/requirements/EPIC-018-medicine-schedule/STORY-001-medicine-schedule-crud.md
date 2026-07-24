# STORY-001: Medicine Schedule CRUD - Add, Manage & Remove Medicines

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-001 |
| **Epic** | EPIC-018 - Medicine Schedule |
| **Priority** | P1 |
| **Complexity** | L |
| **Status** | Draft |

---

## Overview

This story defines the core medicine schedule management API for the Namma MedMate Customer App. A customer can add any medicine - free text or linked to the master catalog - to the schedule for themselves or a care circle member. Each medicine entry specifies the dose, form, multiple dose slots with individual reminder times, food instruction, duration, and supply tracking settings. The schedule powers the reminder engine (STORY-003) and refill alerts (STORY-005).

---

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| `customer` | Full read + write | Manage own schedule and care circle members' schedules |
| `pharmacy_owner` | No access | Not applicable |
| `admin_support` | Read-only | Support view for customer care enquiries |

---

## Business Rules

1. **Medicine name is free text with catalog autocomplete.** `medicine_name` is always stored as free text. If the customer selects from the master catalog autocomplete, `master_medicine_id` is also stored for catalog-linked features (reorder link, drug interaction checks in future). Catalog linking is optional.
2. **Member-scoped schedule.** Every `ScheduleMedicine` belongs to a `member_id` (a `CareCircleMember`). The customer's own member record is created on first schedule addition if it doesn't already exist. Passing `member_id = null` defaults to the customer's self member.
3. **Multiple dose slots per day.** `dose_slots` is a JSONB array of `{ slot, reminder_time }` pairs. A medicine can have 1 to 6 dose slots per day (e.g., morning + evening). Each slot has its own reminder time.
4. **ONGOING vs DAYS duration.** `duration_type = ONGOING` means the medicine has no planned end date. `duration_type = DAYS` requires `duration_days > 0` and the system computes `ended_on_date = started_on_date + duration_days`.
5. **Supply tracking.** `units_in_hand` is the current count of physical units the customer has. `refill_remind_at_units` triggers a refill alert when `units_in_hand ? refill_remind_at_units`. Both fields are optional (0 means supply tracking disabled).
6. **Soft delete only.** `DELETE /schedule/medicines/:medicine_id` sets `is_active = false` and records `ended_on_date = today`. All dose logs and adherence history are preserved. Reminders are cancelled for the medicine.
7. **Medicine ownership.** A customer may only manage medicines for their own care circle members. Accessing another customer's `member_id` returns 403 `FORBIDDEN`.
8. **Reminder recalculation on save.** Adding, editing, or deleting a medicine triggers a background job to recalculate the `ReminderSchedule` for the next 7 days for that member (see STORY-003).

---

## API Endpoints

### 1. List Schedule Medicines

```
GET /api/v1/schedule/medicines
```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 60 req/min

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `member_id` | UUID | self | Care circle member ID; defaults to customer's self member |
| `is_active` | boolean | `true` | Include active/inactive medicines |

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "member": {
      "member_id": "uuid",
      "name": "Priya Sharma",
      "relationship": "SELF"
    },
    "medicines": [
      {
        "medicine_id": "uuid",
        "medicine_name": "Metformin 500mg",
        "master_medicine_id": "uuid",
        "strength": "500mg",
        "dose": "1 tablet",
        "form": "TABLET",
        "dose_slots": [
          { "slot": "MORNING", "reminder_time": "08:00" },
          { "slot": "NIGHT", "reminder_time": "21:00" }
        ],
        "food_instruction": "AFTER",
        "duration_type": "ONGOING",
        "started_on_date": "2026-01-15",
        "ended_on_date": null,
        "condition_name": "Type 2 Diabetes",
        "prescribed_by": "Dr. Anil Sharma",
        "units_in_hand": 30,
        "refill_remind_at_units": 10,
        "is_active": true,
        "today_doses": {
          "total": 2,
          "taken": 1,
          "upcoming": 1
        }
      }
    ],
    "total_medicines": 3
  }
}
```

---

### 2. Add Medicine to Schedule

```
POST /api/v1/schedule/medicines
```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 30 req/min

**Request Body (application/json):**

```json
{
  "member_id": "UUID - optional; defaults to self",
  "medicine_name": "string max 200 - required",
  "master_medicine_id": "UUID - optional",
  "strength": "string max 50 - optional (e.g. '500mg')",
  "dose": "string max 100 - required (e.g. '1 tablet', '5ml')",
  "form": "TABLET | SYRUP | CAPSULE | DROPS | INJECTION | OTHER - required",
  "dose_slots": [
    {
      "slot": "MORNING | AFTERNOON | EVENING | NIGHT | CUSTOM - required",
      "reminder_time": "HH:MM 24h format - required"
    }
  ],
  "food_instruction": "BEFORE | AFTER | ANY - required",
  "duration_type": "ONGOING | DAYS - required",
  "duration_days": "integer > 0 - required if duration_type = DAYS",
  "started_on_date": "date YYYY-MM-DD - required",
  "condition_name": "string max 200 - optional",
  "prescribed_by_doctor": "string max 200 - optional",
  "refill_units_in_hand": "integer ? 0 - optional, default 0",
  "refill_remind_at_units": "integer ? 0 - optional, default 0",
  "notes": "string max 500 - optional"
}
```

**Success Response - 201 Created:**

```json
{
  "success": true,
  "data": {
    "medicine_id": "uuid",
    "medicine_name": "Metformin 500mg",
    "member_id": "uuid",
    "dose_slots": [
      { "slot": "MORNING", "reminder_time": "08:00" },
      { "slot": "NIGHT", "reminder_time": "21:00" }
    ],
    "duration_type": "ONGOING",
    "started_on_date": "2026-07-24",
    "reminders_scheduled": 14,
    "created_at": "2026-07-24T07:00:00Z"
  }
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `MISSING_DURATION_DAYS` | `duration_type = DAYS` but `duration_days` not provided |
| 400 | `INVALID_REMINDER_TIME` | `reminder_time` not valid HH:MM format |
| 400 | `TOO_MANY_DOSE_SLOTS` | More than 6 dose slots provided |
| 403 | `MEMBER_ACCESS_DENIED` | `member_id` does not belong to the customer's care circle |
| 404 | `MEMBER_NOT_FOUND` | `member_id` not found |

---

### 3. Get Medicine Detail

```
GET /api/v1/schedule/medicines/:medicine_id
```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 120 req/min

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "medicine_id": "uuid",
    "member": { "member_id": "uuid", "name": "Priya Sharma", "relationship": "SELF" },
    "medicine_name": "Metformin 500mg",
    "strength": "500mg",
    "dose": "1 tablet",
    "form": "TABLET",
    "dose_slots": [
      { "slot": "MORNING", "reminder_time": "08:00" },
      { "slot": "NIGHT", "reminder_time": "21:00" }
    ],
    "food_instruction": "AFTER",
    "duration_type": "ONGOING",
    "started_on_date": "2026-01-15",
    "ended_on_date": null,
    "condition_name": "Type 2 Diabetes",
    "prescribed_by": "Dr. Anil Sharma",
    "units_in_hand": 30,
    "refill_remind_at_units": 10,
    "approx_days_left": 15,
    "today_doses": { "total": 2, "taken": 1, "skipped": 0, "missed": 0, "upcoming": 1 },
    "course_progress_pct": 100,
    "this_week_adherence_pct": 92.8,
    "notes": "Take with breakfast",
    "is_active": true,
    "created_at": "2026-01-15T00:00:00Z"
  }
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 403 | `MEDICINE_ACCESS_DENIED` | Medicine belongs to another customer |
| 404 | `MEDICINE_NOT_FOUND` | Medicine ID not found |

---

### 4. Update Medicine Schedule

```
PATCH /api/v1/schedule/medicines/:medicine_id
```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 30 req/min

**Request Body (application/json):** Same optional fields as POST (excluding `member_id`).

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "medicine_id": "uuid",
    "updated_fields": ["dose_slots", "refill_remind_at_units"],
    "reminders_rescheduled": true,
    "updated_at": "2026-07-24T08:00:00Z"
  }
}
```

---

### 5. Remove Medicine from Schedule (Soft Delete)

```
DELETE /api/v1/schedule/medicines/:medicine_id
```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 20 req/min

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "medicine_id": "uuid",
    "is_active": false,
    "ended_on_date": "2026-07-24",
    "reminders_cancelled": 14
  }
}
```

---

## Data Models

### ScheduleMedicine

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID | PK | Unique schedule entry |
| `customer_id` | UUID | FK ? Customer, NOT NULL | Owning customer account |
| `member_id` | UUID | FK ? CareCircleMember, NOT NULL | Schedule member |
| `master_medicine_id` | UUID | FK ? MasterMedicine, nullable | Catalog link (optional) |
| `medicine_name` | VARCHAR(200) | NOT NULL | Free-text medicine name |
| `strength` | VARCHAR(50) | nullable | Dose strength (e.g., 500mg) |
| `dose` | VARCHAR(100) | NOT NULL | Dose instruction (e.g., 1 tablet) |
| `form` | ENUM | NOT NULL | TABLET / SYRUP / CAPSULE / DROPS / INJECTION / OTHER |
| `dose_slots` | JSONB | NOT NULL, min 1 element | Array of `{slot, reminder_time}` |
| `food_instruction` | ENUM | NOT NULL | BEFORE / AFTER / ANY |
| `duration_type` | ENUM | NOT NULL | ONGOING / DAYS |
| `duration_days` | INTEGER | > 0, nullable | Required when DAYS |
| `started_on_date` | DATE | NOT NULL | Course start date |
| `ended_on_date` | DATE | nullable | Computed or manual end date |
| `condition_name` | VARCHAR(200) | nullable | Illness/condition name |
| `prescribed_by` | VARCHAR(200) | nullable | Doctor name |
| `units_in_hand` | INTEGER | ? 0, default 0 | Current physical supply units |
| `refill_remind_at_units` | INTEGER | ? 0, default 0 | Alert threshold (0 = disabled) |
| `notes` | TEXT | nullable | Patient notes |
| `is_active` | BOOLEAN | NOT NULL, default true | Active/archived |
| `created_at` | TIMESTAMPTZ | NOT NULL | Creation timestamp |
| `updated_at` | TIMESTAMPTZ | NOT NULL | Last update timestamp |

---

## Acceptance Criteria

- [ ] Given `POST /schedule/medicines` with `duration_type = DAYS` and no `duration_days`, then a 400 `MISSING_DURATION_DAYS` error is returned.
- [ ] Given `POST /schedule/medicines` with `dose_slots` containing 7 entries, then a 400 `TOO_MANY_DOSE_SLOTS` error is returned.
- [ ] Given a customer adds a medicine for a `member_id` belonging to a different customer, then a 403 `MEMBER_ACCESS_DENIED` error is returned.
- [ ] Given `POST /schedule/medicines` with valid inputs, then `reminders_scheduled` in the response equals the total number of reminder notifications created for the next 7 days.
- [ ] Given `DELETE /schedule/medicines/:id`, then `is_active = false`, `ended_on_date = today`, and all future `ReminderSchedule` entries for that medicine are cancelled.
- [ ] Given `GET /schedule/medicines` with no `member_id`, then the response defaults to the customer's self member schedule.
- [ ] Given `PATCH /schedule/medicines/:id` updating `dose_slots`, then the existing `ReminderSchedule` for the next 7 days is deleted and recalculated.
- [ ] Given `GET /schedule/medicines/:id`, then `approx_days_left` equals `units_in_hand / doses_per_day` (computed correctly for a 2-dose-per-day medicine).

---

## Dependencies

- **EPIC-018 / STORY-002 (Care Circle):** `member_id` must reference a valid `CareCircleMember` for the authenticated customer.
- **EPIC-018 / STORY-003 (Reminder Engine):** Medicine add/update/delete triggers reminder recalculation.
- **EPIC-018 / STORY-005 (Refill Alerts):** `units_in_hand` and `refill_remind_at_units` are managed here.
- **EPIC-001 (Master Medicine Catalog):** Optional `master_medicine_id` links to catalog.

---

## Notes

- The `dose_slots` JSONB schema must be validated on write. Valid slot values: MORNING, AFTERNOON, EVENING, NIGHT, CUSTOM. `reminder_time` must be a valid HH:MM string in 24-hour format.
- `units_in_hand` decrements automatically via a nightly job that subtracts `total_daily_doses` from each active medicine with `units_in_hand > 0`. Manual decrements also occur when a dose is marked TAKEN.
- `approx_days_left` is a display-only computed value: `units_in_hand / (count of active dose_slots)`.
