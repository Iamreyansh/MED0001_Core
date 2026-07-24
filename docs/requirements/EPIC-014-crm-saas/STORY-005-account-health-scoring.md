# STORY-005: Account Health Scoring

| Field | Value |
|---|---|
| Story ID | EPIC-014-STORY-005 |
| Epic | EPIC-014 CRM SaaS |
| Title | Account Health Scoring |
| Priority | P1 |
| Status | Planned |
| Created | 2026-07-24 |
| Last Updated | 2026-07-24 |

---

## Overview

Account Health Scoring provides Customer Success Managers (CSMs) in Admin HQ with a data-driven view of every pharmacy subscriber's engagement, satisfaction, and business performance. Each account receives a composite health score (0-100) computed daily from four dimensions: product usage (30%), billing health (25%), support satisfaction (25%), and business performance (20%). Accounts below 50 are flagged AT_RISK; those below 40 auto-trigger a CSM save play task. The at-risk list, sorted by MRR at risk, gives CSMs a prioritised work queue. All save play actions (calls, discounts, training sessions) are logged for accountability and outcome tracking.

---

## User Roles

| Role | Capability |
|---|---|
| `admin_super` | View all health scores; trigger save plays; view platform KPIs |
| `admin_operations` | View account health; log save plays; view at-risk list |
| `admin_finance` | Read-only; view MRR at risk |

---

## Business Rules

1. **Health score formula** - `overall_score = (product_usage - 0.30) + (billing_health - 0.25) + (support_satisfaction - 0.25) + (business_performance - 0.20)` where each component is 0-100.
2. **Billing health component** - billing_health = 100 if PAID and no overdue invoices; 70 if invoice DUE (within grace); 0 if OVERDUE or DUNNING.
3. **Support satisfaction component** - derived from: customer NPS (if collected) - weight + (1 ? open_critical_tickets / total_tickets) - weight.
4. **Business performance component** - for marketplace-linked accounts: GMV growth rate vs. previous period; for ERP-only accounts: invoice volume growth in ERP.
5. **Product usage component** - computed from module event counts in the last 30 days relative to expected usage for the plan; high usage = high score.
6. **Daily recomputation** - health scores are recomputed nightly at 03:00 IST; `computed_at` is updated on each run.
7. **AT_RISK threshold** - score < 50 = AT_RISK; score < 40 = CHURNING; auto-trigger save play task when score drops to < 40 for the first time in a period.
8. **Health bands** - HEALTHY (75-100), MODERATE (50-74), AT_RISK (25-49), CHURNING (0-24).
9. **Save play logging** - CSMs log save play actions against the account; each action has `action_type`, `outcome`, and `notes`; outcome tracked for success analysis.
10. **MRR at risk** - sum of MRR from AT_RISK + CHURNING accounts; shown on the at-risk dashboard KPI.

---

## API Endpoints

### 1. Get Account Health Score (Admin)

```
GET /api/v1/admin/crm/accounts/:id/health
Authorization: Bearer JWT (admin_super | admin_operations | admin_finance)
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "account_id": "acc_uuid_001",
    "pharmacy_name": "Apollo Pharmacy HSR",
    "overall_score": 42,
    "health_band": "AT_RISK",
    "components": {
      "product_usage": 55,
      "billing_health": 70,
      "support_satisfaction": 30,
      "business_performance": 20
    },
    "risk_factors": [
      "Low module adoption (only 3 of 8 modules used in last 30 days)",
      "2 open L2 support tickets exceeding SLA",
      "GMV declined 22% vs. prior month"
    ],
    "recommended_actions": [
      "Schedule a training session on inventory and billing modules",
      "Escalate open support tickets to resolution",
      "Offer a 1-month discount to retain account"
    ],
    "computed_at": "2026-07-24T03:00:00Z"
  }
}
```

---

### 2. List At-Risk Accounts (Admin)

```
GET /api/v1/admin/crm/at-risk
Authorization: Bearer JWT (admin_super | admin_operations | admin_finance)
```

**Query Parameters**

| Parameter | Type | Description |
|---|---|---|
| `health_band` | string | `AT_RISK`, `CHURNING` |
| `page` | integer | Default 1 |
| `limit` | integer | Default 20 |

**Response 200**
```json
{
  "success": true,
  "data": {
    "total_mrr_at_risk_rs": 48270,
    "accounts": [
      {
        "account_id": "acc_uuid_001",
        "pharmacy_name": "Apollo Pharmacy HSR",
        "plan": "RETAIL_PRO",
        "mrr_rs": 1499,
        "overall_score": 42,
        "health_band": "AT_RISK",
        "renewal_date": "2026-08-15",
        "last_save_play_at": null,
        "assigned_csm": "Sneha Rao"
      }
    ]
  },
  "meta": { "page": 1, "limit": 20, "total": 32 }
}
```

---

### 3. Log Save Play Action (Admin)

```
POST /api/v1/admin/crm/accounts/:id/health/save-play
Authorization: Bearer JWT (admin_super | admin_operations)
Content-Type: application/json
```

**Request Body**
```json
{
  "action_type": "CALL",
  "outcome": "Pharmacy owner agreed to a training session next week.",
  "notes": "CSM Sneha spoke with Ramesh Kumar for 25 minutes. He was unaware of the Advanced Analytics module."
}
```

**Response 201**
```json
{
  "success": true,
  "data": {
    "save_play_id": "sp_uuid_001",
    "account_id": "acc_uuid_001",
    "action_type": "CALL",
    "logged_by": "admin_uuid_002",
    "logged_at": "2026-07-24T14:00:00Z"
  }
}
```

**Error Responses**

| HTTP | Error Code | Description |
|---|---|---|
| 404 | `ACCOUNT_NOT_FOUND` | Account ID does not exist |
| 422 | `INVALID_ACTION_TYPE` | Not in allowed enum |

---

### 4. Get Account Usage Data (Admin)

```
GET /api/v1/admin/crm/accounts/:id/usage
Authorization: Bearer JWT (admin_super | admin_operations)
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "account_id": "acc_uuid_001",
    "modules": [
      {
        "module": "BILLING",
        "events_per_day": [
          { "date": "2026-07-23", "count": 45 },
          { "date": "2026-07-24", "count": 38 }
        ],
        "last_active_at": "2026-07-24T10:00:00Z"
      },
      {
        "module": "INVENTORY",
        "events_per_day": [
          { "date": "2026-07-23", "count": 12 },
          { "date": "2026-07-24", "count": 8 }
        ],
        "last_active_at": "2026-07-24T09:00:00Z"
      }
    ],
    "overall_last_active_at": "2026-07-24T10:00:00Z"
  }
}
```

---

### 5. Get Platform Health KPIs (Admin)

```
GET /api/v1/admin/crm/health-kpis
Authorization: Bearer JWT (admin_super | admin_operations | admin_finance)
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "avg_health_score": 68.4,
    "healthy_pct": 62.0,
    "moderate_pct": 24.0,
    "at_risk_count": 32,
    "churning_count": 8,
    "mrr_at_risk_rs": 48270,
    "accounts_with_open_save_plays": 18,
    "computed_at": "2026-07-24T03:00:00Z"
  }
}
```

---

## Data Model

### AccountHealthScore

| Field | Type | Constraints | Description |
|---|---|---|---|
| `id` | UUID v4 | PK | Record identifier |
| `account_id` | UUID | FK ? crm_accounts, UNIQUE | One record per account |
| `overall_score` | DECIMAL(5,2) | NOT NULL | 0-100 composite score |
| `product_usage_score` | DECIMAL(5,2) | NOT NULL | Component (0-100) |
| `billing_health_score` | DECIMAL(5,2) | NOT NULL | Component (0-100) |
| `support_satisfaction_score` | DECIMAL(5,2) | NOT NULL | Component (0-100) |
| `business_performance_score` | DECIMAL(5,2) | NOT NULL | Component (0-100) |
| `health_band` | ENUM | NOT NULL | `HEALTHY`, `MODERATE`, `AT_RISK`, `CHURNING` |
| `risk_factors` | TEXT[] | DEFAULT {} | Risk factor strings |
| `recommended_actions` | TEXT[] | DEFAULT {} | Action recommendation strings |
| `computed_at` | TIMESTAMPTZ | NOT NULL | Last compute time |

### AccountHealthSnapshot (history)

| Field | Type | Constraints | Description |
|---|---|---|---|
| `id` | UUID v4 | PK | Snapshot record |
| `account_id` | UUID | FK ? crm_accounts | Account |
| `overall_score` | DECIMAL(5,2) | NOT NULL | Score at snapshot |
| `health_band` | ENUM | NOT NULL | Band at snapshot |
| `snapshot_date` | DATE | NOT NULL | Snapshot date |

### SavePlay

| Field | Type | Constraints | Description |
|---|---|---|---|
| `id` | UUID v4 | PK | Save play record |
| `account_id` | UUID | FK ? crm_accounts | Account |
| `action_type` | ENUM | NOT NULL | `CALL`, `EMAIL`, `TRAINING`, `DISCOUNT_OFFERED`, `PLAN_ADJUSTED` |
| `outcome` | TEXT | NOT NULL | Outcome description |
| `notes` | TEXT | NULLABLE | Additional notes |
| `logged_by` | UUID | FK ? admin_users | CSM who logged |
| `logged_at` | TIMESTAMPTZ | DEFAULT NOW() | Action timestamp |

---

## Acceptance Criteria

1. Health score for an account with all invoices PAID, high module usage, no open tickets, and growing GMV scores ? 75 (HEALTHY).
2. Account with OVERDUE invoice has `billing_health_score = 0`, dragging overall score below 50 into AT_RISK band.
3. At-risk list returns only accounts with `overall_score < 50`, sorted by `mrr_rs` descending.
4. Save play logged by CSM appears in account's save play history and updates `last_save_play_at`.
5. Score < 40 auto-triggers a save play task notification to the assigned CSM (via notification engine).
6. Platform health KPIs show correct `at_risk_count` matching the at-risk list total.
7. Usage chart returns per-module event counts for the last 30 days.
8. Health scores are recomputed nightly; `computed_at` is within 24 hours.
9. `health_band` correctly maps: 75-100 = HEALTHY, 50-74 = MODERATE, 25-49 = AT_RISK, 0-24 = CHURNING.
10. `mrr_at_risk_rs` = sum of MRR from AT_RISK + CHURNING accounts and matches manual arithmetic.

---

## Dependencies

| Dependency | Description |
|---|---|
| Subscription Management (STORY-002) | Billing status for billing_health component |
| Billing Module (STORY-003) | Invoice payment status |
| Feature Adoption Metering (STORY-006) | Module event counts for product_usage |
| Support Tickets (EPIC-015-001) | Open tickets for support_satisfaction |
| Order Module | GMV data for business_performance |
| Notification Engine | Auto-trigger save play alerts |
| Scheduled Job Runner | Nightly recomputation |

---

## Notes

- Health snapshots are stored daily in `AccountHealthSnapshot` to power trend charts in future versions.
- `risk_factors` and `recommended_actions` are rule-based: e.g. if `product_usage_score < 30`, add risk factor "Low module adoption" and action "Schedule training session".
- NPS scores (for support_satisfaction) are collected via post-resolution CSAT surveys from EPIC-015.
