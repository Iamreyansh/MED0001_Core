# EPIC-018: Medicine Schedule

| Attribute | Value |
|-----------|-------|
| **Epic ID** | EPIC-018 |
| **Module** | Customer App |
| **Plan Availability** | Available to all customers (no plan gating) |
| **Priority** | P1 |
| **Status** | Draft |

---

## Overview

EPIC-018 covers the Medicine Schedule feature of the Namma MedMate Customer App - a personal medication management system for patients and their families. Customers can add medicines to their schedule with dose slots, set reminders, track adherence, manage supply levels, and receive refill alerts. The Care Circle feature extends scheduling to family members (spouse, parents, children) managed under one account. Together, these five stories transform the app from a simple medicine-ordering platform into a trusted, long-term health companion.

---

## Stories

| Story ID | Title | Priority | Complexity | Status |
|----------|-------|----------|------------|--------|
| STORY-001 | Medicine Schedule CRUD - Add, manage, remove medicines | P1 | L | Draft |
| STORY-002 | Care Circle Management - Family member scheduling | P1 | M | Draft |
| STORY-003 | Dose Reminder Engine - Push notification scheduling | P0 | L | Draft |
| STORY-004 | Adherence Tracking - Stats, calendar, streaks | P2 | M | Draft |
| STORY-005 | Refill Alerts - Supply tracking and reorder | P1 | M | Draft |

---

## Key Data Entities

| Entity | Owner Story | Description |
|--------|-------------|-------------|
| `CareCircleMember` | STORY-002 | Family member profile under a customer account |
| `ScheduleMedicine` | STORY-001 | Medicine added to a member's schedule |
| `DoseLog` | STORY-003 | Per-dose event log (taken/skipped/missed) |
| `ReminderSchedule` | STORY-003 | Scheduled push notification for a dose |

---

## Dependencies

- **EPIC-001 (Master Medicine Catalog):** `master_medicine_id` optionally links a scheduled medicine to the global catalog for accurate product information and reorder.
- **EPIC-002 (Customer App - Orders):** Refill intents (STORY-005) deep-link to the order flow.
- **EPIC-010 (Notifications):** Push, SMS notifications for dose reminders and refill alerts are dispatched via the notification service (FCM/APNs).
- **EPIC-003 (Customer Profile):** The customer's `member_id = customer_id` is the root Care Circle member (self).

---

## Notes

- All schedule and adherence data is customer-private. One account can have up to 10 care circle members (including self).
- `member_id` references a `CareCircleMember` (not an independent customer account). Reminders always go to the account holder's phone.
- Dose logs are append-only and immutable after 24 hours.
