# STORY-007: Renewal and Churn Management

| Field | Value |
|---|---|
| Story ID | EPIC-014-STORY-007 |
| Epic | EPIC-014 CRM SaaS |
| Title | Renewal and Churn Management |
| Priority | P1 |
| Status | Planned |
| Created | 2026-07-24 |
| Last Updated | 2026-07-24 |

---

## Overview

Renewal and Churn Management gives CSMs and SaaS leadership a forward-looking view of upcoming renewals and a retrospective analysis of churn. The renewal pipeline dashboard surfaces accounts renewing in the next 30 days with risk scores, health indicators, and CSM assignments, enabling proactive interventions before lapse. Churned accounts trigger a win-back sequence automatically 7 days after churn. The churn analysis view breaks down lost accounts by reason (price, features, competitor, etc.) and cohort, surfacing actionable patterns for the product and sales teams. All churn decisions are logged via a structured survey.

---

## User Roles

| Role | Capability |
|---|---|
| `admin_super` | Full access; admin-trigger renewals; waive fees |
| `admin_operations` | View renewals and churn; log churn surveys; trigger manual renewal |
| `admin_finance` | Read-only; MRR churn and cohort analysis |

---

## Business Rules

1. **Auto-renew timing** - auto-renew payment is attempted 3 days before the renewal date; if it succeeds, the renewal date advances by the billing cycle.
2. **At-risk renewal trigger** - accounts with `health_score < 50` and a renewal in the next 30 days generate a CSM notification immediately (not waiting for the 3-day pre-renewal window).
3. **Logo churn formula** - `logo_churn_pct = (churned_logos_in_period / start_of_period_logos) - 100`.
4. **MRR churn** - `mrr_churned = SUM(MRR from accounts that churned in the period)`.
5. **Win-back sequence** - 7 days after an account reaches EXPIRED status without payment, an automated win-back email + WhatsApp sequence is triggered (via automation engine).
6. **Churn survey** - upon cancellation (cancel endpoint), a churn survey is sent to the pharmacy owner; CSMs can also log a churn reason on behalf of the account via this module's API.
7. **Manual renewal** - admin can trigger a manual renewal for an account (with optional fee waiver); this bypasses auto-renew logic and directly generates the invoice and processes payment.
8. **Monthly churn reports** - churn reports are generated on the first day of each month covering the prior month; accessible via the churn analysis endpoint.
9. **Save play banner** - on the renewal pipeline dashboard, a banner highlights accounts where a save play has been initiated in the past 7 days.
10. **Risk level classification** - renewal risk level is derived from health score: health_score ? 75 = LOW; 50-74 = MEDIUM; < 50 = HIGH.

---

## API Endpoints

### 1. Renewal Pipeline Dashboard (Admin)

```
GET /api/v1/admin/crm/renewals
Authorization: Bearer JWT (admin_super | admin_operations | admin_finance)
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "chips": {
      "renewing_in_30d": 42,
      "mrr_at_risk_rs": 58380,
      "churned_logos_this_month": 8,
      "logo_churn_pct": 1.32,
      "mrr_churned_this_month_rs": 10194
    },
    "save_play_banner": {
      "active_save_plays": 12,
      "message": "12 active save plays in progress - track outcomes in account health."
    },
    "churn_reasons_chart": [
      { "reason": "PRICE", "count": 3 },
      { "reason": "FEATURES", "count": 2 },
      { "reason": "NOT_USING", "count": 2 },
      { "reason": "OTHER", "count": 1 }
    ],
    "upcoming_renewals": [
      {
        "account_id": "acc_uuid_001",
        "pharmacy_name": "Apollo Pharmacy HSR",
        "plan": "RETAIL_PRO",
        "mrr_rs": 1499,
        "renewal_date": "2026-08-05",
        "auto_renew": true,
        "risk_level": "HIGH",
        "health_score": 42,
        "assigned_csm": "Sneha Rao"
      }
    ],
    "churn_log": [
      {
        "account_id": "acc_uuid_099",
        "pharmacy_name": "City Medicals",
        "plan": "STARTER",
        "mrr_rs": 699,
        "churned_at": "2026-07-18T00:00:00Z",
        "reason": "NOT_USING"
      }
    ]
  }
}
```

---

### 2. List Upcoming Renewals (Admin)

```
GET /api/v1/admin/crm/renewals/upcoming
Authorization: Bearer JWT (admin_super | admin_operations | admin_finance)
```

**Query Parameters**

| Parameter | Type | Description |
|---|---|---|
| `days` | integer | Renewal window (default 30, max 90) |
| `risk_level` | string | `LOW`, `MEDIUM`, `HIGH` |
| `csm_id` | UUID | Filter by assigned CSM |
| `page` | integer | Default 1 |
| `limit` | integer | Default 20 |

**Response 200**
```json
{
  "success": true,
  "data": {
    "upcoming_renewals": [
      {
        "account_id": "acc_uuid_001",
        "pharmacy_name": "Apollo Pharmacy HSR",
        "plan": "RETAIL_PRO",
        "mrr_rs": 1499,
        "renewal_date": "2026-08-05",
        "days_until_renewal": 12,
        "auto_renew": true,
        "risk_level": "HIGH",
        "health_score": 42,
        "last_save_play_at": "2026-07-24T14:00:00Z",
        "assigned_csm": "Sneha Rao"
      }
    ]
  },
  "meta": { "page": 1, "limit": 20, "total": 42 }
}
```

---

### 3. Admin-Trigger Manual Renewal (Admin)

```
POST /api/v1/admin/crm/accounts/:id/renew
Authorization: Bearer JWT (admin_super | admin_operations)
Content-Type: application/json
```

**Request Body**
```json
{
  "waive_fee": false,
  "reason": "Customer requested early renewal to switch to annual billing."
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "account_id": "acc_uuid_001",
    "invoice_id": "inv_uuid_003",
    "amount_charged_rs": 1766,
    "new_renewal_date": "2026-09-05",
    "waive_fee": false,
    "renewed_by": "admin_uuid_001",
    "renewed_at": "2026-07-24T10:00:00Z"
  }
}
```

**Error Responses**

| HTTP | Error Code | Description |
|---|---|---|
| 400 | `SUBSCRIPTION_NOT_DUE` | Renewal date is not within 7 days |
| 404 | `ACCOUNT_NOT_FOUND` | Account does not exist |

---

### 4. Log Churn Survey (Admin)

```
POST /api/v1/admin/crm/accounts/:id/churn-survey
Authorization: Bearer JWT (admin_super | admin_operations)
Content-Type: application/json
```

**Request Body**
```json
{
  "reason": "PRICE",
  "notes": "Pharmacy found a cheaper alternative at Rs 499/month. Unwilling to negotiate."
}
```

**Response 201**
```json
{
  "success": true,
  "data": {
    "churn_survey_id": "cs_uuid_001",
    "account_id": "acc_uuid_001",
    "reason": "PRICE",
    "logged_by": "admin_uuid_002",
    "logged_at": "2026-07-24T11:00:00Z"
  }
}
```

---

### 5. Churn Analysis (Admin)

```
GET /api/v1/admin/crm/churn-analysis
Authorization: Bearer JWT (admin_super | admin_operations | admin_finance)
```

**Query Parameters**

| Parameter | Type | Description |
|---|---|---|
| `period` | string | `last_30d`, `last_90d`, `last_6m` |

**Response 200**
```json
{
  "success": true,
  "data": {
    "period": "last_90d",
    "churn_reasons_chart": [
      { "reason": "PRICE", "count": 12, "pct": 37.5 },
      { "reason": "FEATURES", "count": 8, "pct": 25.0 },
      { "reason": "MOVING_TO_COMPETITOR", "count": 6, "pct": 18.75 },
      { "reason": "NOT_USING", "count": 4, "pct": 12.5 },
      { "reason": "CLOSING_BUSINESS", "count": 1, "pct": 3.125 },
      { "reason": "OTHER", "count": 1, "pct": 3.125 }
    ],
    "cohort_churn_rates": [
      { "cohort_month": "2026-01", "month_1_churn_pct": 2.1, "month_3_churn_pct": 5.8, "month_6_churn_pct": 9.2 },
      { "cohort_month": "2026-04", "month_1_churn_pct": 1.8, "month_3_churn_pct": null }
    ],
    "at_risk_indicators": [
      { "indicator": "Low module adoption (<20%)", "churned_with_this": 22, "pct_of_churned": 68.75 },
      { "indicator": "Missed 2+ payment cycles", "churned_with_this": 14, "pct_of_churned": 43.75 }
    ]
  }
}
```

---

## Data Model

### ChurnSurvey

| Field | Type | Constraints | Description |
|---|---|---|---|
| `id` | UUID v4 | PK | Survey record |
| `account_id` | UUID | FK ? crm_accounts | Churned account |
| `reason` | ENUM | NOT NULL | `PRICE`, `FEATURES`, `MOVING_TO_COMPETITOR`, `CLOSING_BUSINESS`, `NOT_USING`, `OTHER` |
| `notes` | TEXT | NULLABLE | Additional context |
| `logged_by` | UUID | NULLABLE FK ? admin_users | CSM (null if self-reported) |
| `logged_at` | TIMESTAMPTZ | DEFAULT NOW() | Survey submission |

### RenewalRisk (computed view)

| Field | Type | Description |
|---|---|---|
| `account_id` | UUID | Account |
| `renewal_date` | DATE | Next renewal |
| `days_until_renewal` | INTEGER | Days to renewal |
| `risk_level` | ENUM | `LOW`, `MEDIUM`, `HIGH` |
| `health_score` | DECIMAL | Current health score |
| `mrr_rs` | DECIMAL | MRR from account |

---

## Acceptance Criteria

1. Renewal pipeline shows all accounts renewing in the next 30 days with correct `days_until_renewal`.
2. Accounts with `health_score < 50` are classified as `risk_level = HIGH` in the renewal list.
3. `logo_churn_pct` = churned logos / start-of-month logos - 100 and matches arithmetic.
4. Admin-trigger manual renewal generates an invoice and advances the renewal date by one billing cycle.
5. Churn survey logs with mandatory reason; survey appears in churn analysis counts.
6. Churn reasons chart sums to 100% for the selected period.
7. `at_risk_indicators` in churn analysis shows correlation between churn and leading indicators.
8. Win-back sequence is triggered 7 days after an account reaches EXPIRED status (verifiable via automation engine logs).
9. Save play banner shows count of active save plays in the past 7 days.
10. Monthly churn reports refresh on the first day of each month with prior month's data.

---

## Dependencies

| Dependency | Description |
|---|---|
| Subscription Management (STORY-002) | Renewal date, cancellation status |
| Account Health Scoring (STORY-005) | Health score for risk classification |
| Billing Module (STORY-003) | Invoice generation on manual renewal |
| Automation Engine | Win-back sequence trigger; at-risk CSM notification |
| Notification Engine | Churn survey delivery; win-back messages |

---

## Notes

- Cohort churn rates require a `subscription_cohorts` table keyed by `(account_id, cohort_month)` populated at subscription start.
- `at_risk_indicators` are computed by correlating churn events with account health attributes at the time of churn (30-day lookback).
- Win-back sequence content (messages, offers) is managed in the Automation Engine (EPIC-011); this module only triggers the event.
