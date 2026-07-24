# STORY-004: Lead Pipeline

| Field | Value |
|---|---|
| Story ID | EPIC-014-STORY-004 |
| Epic | EPIC-014 CRM SaaS |
| Title | Lead Pipeline |
| Priority | P1 |
| Status | Planned |
| Created | 2026-07-24 |
| Last Updated | 2026-07-24 |

---

## Overview

Lead Pipeline manages the sales pipeline for converting pharmacy businesses into paying Namma MedMate ERP subscribers. The pipeline follows a five-stage funnel (NEW ? CONTACTED ? DEMO ? TRIAL ? WON), with win probabilities assigned per stage and a weighted MRR forecast across all open leads. Sales representatives work individual leads by advancing stages, logging notes, scheduling demos, and converting won leads directly into active subscriptions. Marketplace pharmacies are auto-created as leads at the CONTACTED stage when they register, providing a built-in warm pipeline. Admin HQ views pipeline KPIs, a stage funnel, and per-rep performance.

---

## User Roles

| Role | Capability |
|---|---|
| `admin_super` | Full CRUD; reassign leads; view all reps |
| `admin_operations` | Create, update, advance leads; mark won/lost; view pipeline |
| `admin_finance` | Read-only pipeline and revenue forecast |

---

## Business Rules

1. **Stage probabilities** - win probability auto-suggested based on stage: NEW=0%, CONTACTED=10%, DEMO=30%, TRIAL=60%, WON=100%; rep can override manually.
2. **Weighted forecast** - `weighted_forecast_mrr = SUM(estimated_mrr - win_probability)` for all OPEN (not won/lost) leads; updated on every lead change.
3. **Marketplace auto-lead** - when a pharmacy registers on the marketplace, a lead is auto-created with `source = MARKETPLACE` and `stage = CONTACTED`; the lead is assigned to the next available rep (round-robin).
4. **Won lead auto-conversion** - marking a lead as WON immediately converts it to a pharmacy subscription with the specified `plan_id` and `billing_cycle`; payment is initiated at conversion.
5. **Lost lead reopen** - a lost lead can be reopened (back to CONTACTED stage) at any time.
6. **Sales cycle tracking** - `sales_cycle_days = days from NEW to WON`; shown in lead detail and used in performance metrics.
7. **Stage advance only** - leads advance forward through stages using the advance endpoint; direct stage jumping is allowed only for `admin_super`.
8. **Activity timeline** - every stage change, note, contact attempt, and won/lost event is logged in the lead's activity timeline with actor and timestamp.
9. **Source tracking** - lead source is captured at creation: ORGANIC (direct signup), REFERRAL (partner referral), AD (paid ad), PARTNER (reseller), MARKETPLACE (auto-created).
10. **Win rate** - `win_rate_pct = (won_leads / (won_leads + lost_leads)) - 100` for the selected period.

---

## Lead Stage Funnel

| Stage | Win Probability | Description |
|---|---|---|
| NEW | 0% | Lead identified; not yet contacted |
| CONTACTED | 10% | First contact made (call/email) |
| DEMO | 30% | Product demo scheduled or completed |
| TRIAL | 60% | Pharmacy activated on 14-day trial |
| WON | 100% | Converted to paid subscriber |
| LOST | 0% | Lead marked lost (terminal state, reopenable) |

---

## API Endpoints

### 1. List Leads (Admin)

```
GET /api/v1/admin/crm/leads
Authorization: Bearer JWT (admin_super | admin_operations | admin_finance)
```

**Query Parameters**

| Parameter | Type | Description |
|---|---|---|
| `stage` | string | Filter by stage |
| `rep_id` | UUID | Filter by assigned rep |
| `source` | string | `ORGANIC`, `REFERRAL`, `AD`, `PARTNER`, `MARKETPLACE` |
| `q` | string | Search by pharmacy name, contact |
| `page` | integer | Default 1 |
| `limit` | integer | Default 20 |

**Response 200**
```json
{
  "success": true,
  "data": {
    "chips": {
      "open_leads": 148,
      "pipeline_mrr_rs": 184200,
      "weighted_forecast_mrr_rs": 52140,
      "avg_deal_mrr_rs": 1244,
      "win_rate_pct": 38.4,
      "avg_sales_cycle_days": 18
    },
    "stage_funnel": {
      "NEW": 32,
      "CONTACTED": 54,
      "DEMO": 38,
      "TRIAL": 24,
      "WON": 0
    },
    "leads": [
      {
        "id": "lead_uuid_001",
        "pharmacy_name": "Sri Ram Medical",
        "contact_name": "Ramesh Kumar",
        "phone": "+919876543210",
        "stage": "DEMO",
        "win_probability": 30,
        "estimated_mrr_rs": 1499,
        "source": "MARKETPLACE",
        "assigned_rep_id": "admin_uuid_002",
        "assigned_rep_name": "Sneha Rao",
        "created_at": "2026-07-10T09:00:00Z"
      }
    ]
  },
  "meta": { "page": 1, "limit": 20, "total": 148 }
}
```

---

### 2. Create Lead (Admin)

```
POST /api/v1/admin/crm/leads
Authorization: Bearer JWT (admin_super | admin_operations)
Content-Type: application/json
```

**Request Body**
```json
{
  "pharmacy_name": "Sri Ram Medical",
  "contact_name": "Ramesh Kumar",
  "phone": "+919876543210",
  "email": "ramesh@srirammedical.com",
  "source": "REFERRAL",
  "target_plan": "STARTER",
  "estimated_mrr_rs": 699,
  "assigned_rep_id": "admin_uuid_002"
}
```

**Response 201**
```json
{
  "success": true,
  "data": {
    "id": "lead_uuid_002",
    "pharmacy_name": "Sri Ram Medical",
    "stage": "NEW",
    "win_probability": 0,
    "created_at": "2026-07-24T10:00:00Z"
  }
}
```

**Error Responses**

| HTTP | Error Code | Description |
|---|---|---|
| 422 | `INVALID_REP` | `assigned_rep_id` not found or not an admin |
| 409 | `LEAD_ALREADY_EXISTS` | Pharmacy already has an open lead |

---

### 3. Get Lead Detail (Admin)

```
GET /api/v1/admin/crm/leads/:id
Authorization: Bearer JWT (admin_super | admin_operations | admin_finance)
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "id": "lead_uuid_001",
    "pharmacy_name": "Sri Ram Medical",
    "contact_name": "Ramesh Kumar",
    "phone": "+919876543210",
    "email": "ramesh@srirammedical.com",
    "source": "MARKETPLACE",
    "stage": "DEMO",
    "win_probability": 30,
    "estimated_mrr_rs": 1499,
    "target_plan": "RETAIL_PRO",
    "assigned_rep": { "id": "admin_uuid_002", "name": "Sneha Rao" },
    "next_best_action": "Follow up on demo feedback; offer 14-day trial.",
    "activity_timeline": [
      { "event": "CREATED", "at": "2026-07-10T09:00:00Z", "actor": "SYSTEM" },
      { "event": "STAGE_CHANGE", "from": "CONTACTED", "to": "DEMO", "at": "2026-07-15T14:00:00Z", "actor": "Sneha Rao", "notes": "Demo scheduled." }
    ],
    "notes": "Pharmacy owner interested in billing + inventory modules.",
    "created_at": "2026-07-10T09:00:00Z"
  }
}
```

---

### 4. Update Lead (Admin)

```
PATCH /api/v1/admin/crm/leads/:id
Authorization: Bearer JWT (admin_super | admin_operations)
Content-Type: application/json
```

**Request Body**
```json
{
  "assigned_rep_id": "admin_uuid_003",
  "estimated_mrr_rs": 1499,
  "win_probability": 45,
  "notes": "Updated notes after follow-up call."
}
```

**Response 200**
```json
{
  "success": true,
  "data": { "id": "lead_uuid_001", "updated_at": "2026-07-24T10:00:00Z" }
}
```

---

### 5. Advance Lead Stage (Admin)

```
POST /api/v1/admin/crm/leads/:id/advance
Authorization: Bearer JWT (admin_super | admin_operations)
Content-Type: application/json
```

**Request Body**
```json
{
  "notes": "Demo completed; pharmacy owner impressed with billing module."
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "id": "lead_uuid_001",
    "previous_stage": "DEMO",
    "new_stage": "TRIAL",
    "win_probability": 60,
    "advanced_at": "2026-07-24T11:00:00Z"
  }
}
```

**Error Responses**

| HTTP | Error Code | Description |
|---|---|---|
| 400 | `LEAD_ALREADY_WON` | Cannot advance a WON lead |
| 400 | `LEAD_ALREADY_LOST` | Cannot advance a LOST lead |

---

### 6. Mark Lead Won (Admin)

```
POST /api/v1/admin/crm/leads/:id/mark-won
Authorization: Bearer JWT (admin_super | admin_operations)
Content-Type: application/json
```

**Request Body**
```json
{
  "plan_id": "plan_uuid_retail_pro",
  "billing_cycle": "MONTHLY"
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "id": "lead_uuid_001",
    "stage": "WON",
    "subscription_created": true,
    "subscription_id": "sub_uuid_002",
    "sales_cycle_days": 14,
    "won_at": "2026-07-24T11:30:00Z"
  }
}
```

---

### 7. Mark Lead Lost (Admin)

```
POST /api/v1/admin/crm/leads/:id/mark-lost
Authorization: Bearer JWT (admin_super | admin_operations)
Content-Type: application/json
```

**Request Body**
```json
{
  "lost_reason": "PRICE",
  "notes": "Pharmacy owner moved to a competitor; found lower pricing."
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "id": "lead_uuid_001",
    "stage": "LOST",
    "lost_reason": "PRICE",
    "lost_at": "2026-07-24T11:45:00Z"
  }
}
```

---

### 8. Reopen Lost Lead (Admin)

```
POST /api/v1/admin/crm/leads/:id/reopen
Authorization: Bearer JWT (admin_super | admin_operations)
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "id": "lead_uuid_001",
    "stage": "CONTACTED",
    "win_probability": 10,
    "reopened_at": "2026-07-24T12:00:00Z"
  }
}
```

---

## Data Model

### Lead

| Field | Type | Constraints | Description |
|---|---|---|---|
| `id` | UUID v4 | PK | Internal identifier |
| `pharmacy_name` | VARCHAR(200) | NOT NULL | Business name |
| `contact_name` | VARCHAR(100) | NOT NULL | Primary contact |
| `phone` | VARCHAR(15) | NOT NULL | Contact phone |
| `email` | VARCHAR(255) | NULLABLE | Contact email |
| `source` | ENUM | NOT NULL | `ORGANIC`, `REFERRAL`, `AD`, `PARTNER`, `MARKETPLACE` |
| `stage` | ENUM | DEFAULT NEW | `NEW`, `CONTACTED`, `DEMO`, `TRIAL`, `WON`, `LOST` |
| `win_probability` | INTEGER | DEFAULT 0 | 0-100 |
| `estimated_mrr_rs` | DECIMAL(10,2) | NULLABLE | Estimated MRR if won |
| `target_plan` | VARCHAR(20) | NULLABLE | Target plan name |
| `assigned_rep_id` | UUID | NULLABLE FK ? admin_users | Assigned sales rep |
| `notes` | TEXT | NULLABLE | Free-form notes |
| `lost_reason` | ENUM | NULLABLE | `PRICE`, `COMPETITOR`, `NOT_INTERESTED`, `TIMELINE`, `OTHER` |
| `won_at` | TIMESTAMPTZ | NULLABLE | When marked won |
| `lost_at` | TIMESTAMPTZ | NULLABLE | When marked lost |
| `sales_cycle_days` | INTEGER | NULLABLE | Days from NEW to WON |
| `linked_account_id` | UUID | NULLABLE FK ? crm_accounts | Created account on WON |
| `created_at` | TIMESTAMPTZ | DEFAULT NOW() | Lead created |
| `updated_at` | TIMESTAMPTZ | DEFAULT NOW() | Last update |

### LeadActivity

| Field | Type | Constraints | Description |
|---|---|---|---|
| `id` | UUID v4 | PK | Activity record |
| `lead_id` | UUID | FK ? leads | Parent lead |
| `event` | VARCHAR(50) | NOT NULL | Event type (CREATED, STAGE_CHANGE, NOTE, etc.) |
| `from_stage` | VARCHAR(20) | NULLABLE | Previous stage |
| `to_stage` | VARCHAR(20) | NULLABLE | New stage |
| `notes` | TEXT | NULLABLE | Activity notes |
| `actor_id` | UUID | NULLABLE FK ? admin_users | Who performed action |
| `actor_name` | VARCHAR(100) | NULLABLE | Denormalised name |
| `created_at` | TIMESTAMPTZ | DEFAULT NOW() | Event timestamp |

---

## Acceptance Criteria

1. New marketplace pharmacy registration auto-creates a lead with `stage = CONTACTED` and `source = MARKETPLACE`.
2. Admin creates a lead manually; it appears in the pipeline at stage NEW with win probability 0%.
3. Advance endpoint moves lead from CONTACTED ? DEMO, auto-sets win probability to 30%.
4. Mark-won endpoint creates a subscription record and returns `subscription_id`.
5. `weighted_forecast_mrr_rs` = SUM(estimated_mrr - win_probability) and updates after each lead change.
6. Mark-lost lead with `lost_reason = PRICE` records the reason and transitions to LOST.
7. Reopening a LOST lead resets it to CONTACTED with win probability 10%.
8. `avg_sales_cycle_days` is computed from all WON leads in the period.
9. Activity timeline on lead detail shows all stage changes with actor name and timestamp.
10. Advancing a WON or LOST lead returns the appropriate error code.

---

## Dependencies

| Dependency | Description |
|---|---|
| Subscription Management (STORY-002) | Auto-create subscription on mark-won |
| Pharmacy Auth / Onboarding | Marketplace registration trigger |
| Admin User Management | Rep assignment validation |
| Notification Engine | Rep notification on lead assignment |

---

## Notes

- `next_best_action` is a system-generated string based on stage and days-in-stage: e.g. CONTACTED + 3 days ? "Schedule a demo"; TRIAL + 7 days ? "Send trial-to-paid nudge".
- Win rate and pipeline metrics on the list are scoped to the current calendar month by default; filterable by period via query params.
