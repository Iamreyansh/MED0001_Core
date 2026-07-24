# STORY-004: Adherence Tracking - Stats, Calendar, and Streaks

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-004 |
| **Epic** | EPIC-018 - Medicine Schedule |
| **Priority** | P2 |
| **Complexity** | M |
| **Status** | Draft |

---

## Overview

This story provides adherence analytics for the Medicine Schedule feature. Customers can view their overall and per-medicine adherence statistics, a calendar heatmap showing daily compliance, the current streak of perfect-adherence days, and a weekly chart for trend visualization. All adherence data is computed on-demand from the immutable `DoseLog` table - no separate aggregation table is required. These screens motivate patients to stay consistent with their medication routines.

---

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| `customer` | Full read | View own and care circle members' adherence |
| `admin_support` | Read-only | Support view for patient engagement queries |
| `pharmacy_owner` | No access | Not applicable |

---

## Business Rules

1. **Adherence percentage formula.** `adherence_pct = (doses_taken / total_scheduled_doses) - 100`. Skipped doses count as NOT taken (reduce adherence). Missed doses also reduce adherence. Only TAKEN doses increase the numerator.
2. **Days with no scheduled doses are excluded.** If a calendar day has zero active medicines with doses scheduled, it is excluded from the denominator and shown as `NO_DOSES` in the calendar. Such days are neutral and do not affect streak or overall percentages.
3. **Perfect day for streak.** A day is "perfect" when `adherence_pct = 100%` for that day (all scheduled doses taken). The streak counter increments for consecutive perfect days and resets on any non-perfect day (partial, missed, or skipped).
4. **Partial day status.** A day where some but not all doses are taken has `status = PARTIAL`. It breaks a streak but is not considered a full-miss day for motivational messaging.
5. **Adherence data is read-only.** Adherence is computed from `DoseLog` records. There is no adherence-specific writable table. The only way to change adherence data is to update a `DoseLog` record (via STORY-003 mark endpoint, within 24 hours).
6. **Midnight refresh.** Adherence summary values (`this_week_pct`, `current_streak_days`) are cached and refreshed at midnight IST for each customer.
7. **Historical data depth.** Adherence history is available from the first logged dose. Calendar view is paginated by month. Per-medicine history shows all-time data.
8. **Member-scoped.** All adherence endpoints accept `member_id` to retrieve adherence for a specific care circle member.

---

## API Endpoints

### 1. Adherence Summary

```
GET /api/v1/schedule/adherence
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
    "this_week_pct": 88.5,
    "current_streak_days": 5,
    "longest_streak_days": 14,
    "total_days_tracked": 92,
    "all_time_pct": 84.2,
    "monthly_adherence": [
      { "month": "2026-07", "pct": 88.5, "days_tracked": 24 },
      { "month": "2026-06", "pct": 82.1, "days_tracked": 30 },
      { "month": "2026-05", "pct": 79.6, "days_tracked": 31 }
    ]
  }
}
```

---

### 2. Calendar View

```
GET /api/v1/schedule/adherence/calendar
```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 60 req/min

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `member_id` | UUID | self | Care circle member |
| `month` | string | current month | Target month in YYYY-MM format |

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "member": { "member_id": "uuid", "name": "Priya Sharma" },
    "month": "2026-07",
    "month_adherence_pct": 88.5,
    "days": [
      {
        "date": "2026-07-01",
        "total_doses": 4,
        "taken": 4,
        "skipped": 0,
        "missed": 0,
        "pct": 100.0,
        "status": "PERFECT"
      },
      {
        "date": "2026-07-02",
        "total_doses": 4,
        "taken": 3,
        "skipped": 0,
        "missed": 1,
        "pct": 75.0,
        "status": "PARTIAL"
      },
      {
        "date": "2026-07-10",
        "total_doses": 0,
        "taken": 0,
        "skipped": 0,
        "missed": 0,
        "pct": null,
        "status": "NO_DOSES"
      }
    ]
  }
}
```

---

### 3. Per-Medicine Adherence History

```
GET /api/v1/schedule/medicines/:medicine_id/adherence
```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 60 req/min

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "medicine_id": "uuid",
    "medicine_name": "Metformin 500mg",
    "member_name": "Priya Sharma",
    "last_7_days_pct": 92.8,
    "last_30_days_pct": 87.5,
    "all_time_pct": 84.2,
    "total_doses_scheduled": 368,
    "total_doses_taken": 310,
    "total_missed": 34,
    "total_skipped": 24,
    "missed_days_list": [
      "2026-07-12",
      "2026-07-05",
      "2026-06-28"
    ]
  }
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 403 | `MEDICINE_ACCESS_DENIED` | Medicine belongs to another customer |
| 404 | `MEDICINE_NOT_FOUND` | Medicine not found |

---

### 4. Weekly Adherence Chart Data

```
GET /api/v1/schedule/adherence/chart
```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 30 req/min

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `member_id` | UUID | self | Care circle member |
| `weeks` | integer | `12` | Number of weeks (max 52) |

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "member": { "member_id": "uuid", "name": "Priya Sharma" },
    "weeks": [
      {
        "week_start": "2026-07-14",
        "week_end": "2026-07-20",
        "week_label": "Jul 14-20",
        "adherence_pct": 92.8,
        "total_doses": 28,
        "taken": 26,
        "status": "HIGH"
      },
      {
        "week_start": "2026-07-07",
        "week_end": "2026-07-13",
        "week_label": "Jul 7-13",
        "adherence_pct": 78.5,
        "total_doses": 28,
        "taken": 22,
        "status": "MEDIUM"
      }
    ]
  }
}
```

> `status` for chart rendering: `HIGH` (?85%), `MEDIUM` (60-84%), `LOW` (<60%).

---

## Data Models

> Adherence has **no dedicated table**. All values are computed from:
> - `DoseLog` - the source of truth (STORY-003)
> - `ScheduleMedicine` - for scheduled dose counts per day

### Computed Adherence Fields Reference

| Field | Computation | Source |
|-------|-------------|--------|
| `adherence_pct` | `taken / scheduled - 100` | `DoseLog` |
| `current_streak_days` | Consecutive days with `pct = 100%` | `DoseLog` aggregated per date |
| `total_days_tracked` | Days with ? 1 scheduled dose | `DoseLog.dose_date` distinct count |
| `status` per day | PERFECT (100%) / PARTIAL (1-99%) / MISSED (0%) / NO_DOSES | `DoseLog` aggregated per date |
| `month_pct` | Average of daily pct for that month | `DoseLog` |

---

## Acceptance Criteria

- [ ] Given a member with 4 doses scheduled on a day and only 3 taken, when `GET /adherence/calendar` is called, then that day shows `status = PARTIAL` and `pct = 75.0`.
- [ ] Given a day with no scheduled doses, when `GET /adherence/calendar` is called, then that day shows `status = NO_DOSES` and `pct = null`.
- [ ] Given 5 consecutive days with 100% adherence followed by a partial day, when `GET /adherence` is called, then `current_streak_days = 5`.
- [ ] Given `GET /medicines/:id/adherence`, then `all_time_pct = total_doses_taken / total_doses_scheduled - 100` is correctly calculated.
- [ ] Given `GET /adherence/chart?weeks=4`, then exactly 4 weekly rows are returned, each with a correct date range label and adherence percentage.
- [ ] Given a week where `status = HIGH`, then `adherence_pct ? 85%` for that week.
- [ ] Given `GET /adherence` with a `member_id` belonging to a different customer, then a 403 `MEMBER_ACCESS_DENIED` error is returned.
- [ ] Given `monthly_adherence` in the summary, then the months are sorted in reverse-chronological order (most recent first).

---

## Dependencies

- **EPIC-018 / STORY-003 (Dose Reminder Engine):** `DoseLog` is the data source for all adherence computations.
- **EPIC-018 / STORY-002 (Care Circle):** `member_id` scoping for multi-member households.

---

## Notes

- All adherence computations should be performed on read (no pre-aggregation in v1). For customers with 2+ years of history, consider a daily aggregation background job writing to a `DailyAdherenceCache` table.
- The `current_streak_days` value is expensive to compute on-demand for long histories. Consider caching it per customer after midnight refresh.
- Weekly status thresholds (HIGH ?85%, MEDIUM 60-84%, LOW <60%) are platform-defined constants for v1 and should be configurable via admin settings in future.
