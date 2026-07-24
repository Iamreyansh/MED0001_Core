# STORY-008: SaaS Revenue Analytics

| Field | Value |
|---|---|
| Story ID | EPIC-014-STORY-008 |
| Epic | EPIC-014 CRM SaaS |
| Title | SaaS Revenue Analytics |
| Priority | P1 |
| Status | Planned |
| Created | 2026-07-24 |
| Last Updated | 2026-07-24 |

---

## Overview

SaaS Revenue Analytics is the financial command centre for Namma MedMate's pharmacy ERP subscription business. It surfaces the complete SaaS metrics suite - MRR, ARR, NRR, GRR, quick ratio, magic number, LTV/CAC - along with MRR movement bridges, cohort retention grids, and unit economics. All metrics are computed monthly, cached, and accessible to `admin_finance` and `admin_super` roles. The dashboard provides 12-month trends, period comparisons, plan-level breakdowns, and cohort retention visualisations, giving leadership the data needed to understand growth quality, churn pressure, and sales efficiency.

---

## User Roles

| Role | Capability |
|---|---|
| `admin_super` | Full access to all analytics; download reports |
| `admin_finance` | Full access to all analytics; download reports |
| `admin_operations` | Read-only high-level KPIs |

---

## Business Rules

1. **MRR definition** - MRR = sum of monthly recurring revenue from all accounts with `subscription.status = ACTIVE` or `TRIAL`; add-on MRR is included.
2. **ARR** - ARR = MRR - 12 (not sum of annual invoices; normalised monthly).
3. **NRR formula** - `NRR_pct = (MRR_end + expansion_mrr ? churn_mrr) / MRR_start - 100`; includes expansion from upgrades and add-ons; excludes new logo MRR.
4. **GRR formula** - `GRR_pct = (MRR_end ? churn_mrr) / MRR_start - 100`; GRR ? NRR always; excludes expansion.
5. **Quick ratio** - `quick_ratio = (new_mrr + expansion_mrr) / (contraction_mrr + churn_mrr)`.
6. **Magic number** - `magic_number = (MRR_growth - 4) / previous_quarter_sales_and_marketing_spend`; MRR growth is quarter-over-quarter net new MRR.
7. **LTV formula** - `LTV = ARPA - gross_margin_pct / monthly_churn_rate`; monthly churn rate = logo churn rate.
8. **CAC formula** - `CAC = total_sales_and_marketing_spend / new_logos_in_period`.
9. **Caching** - all SaaS metrics are computed monthly by a batch job and cached; the API serves cached results; cache is invalidated and refreshed on the first day of each month.
10. **Access control** - `admin_finance` and `admin_super` roles required; `admin_operations` can read high-level KPIs only.

---

## MRR Movement Bridge Definitions

| Component | Definition |
|---|---|
| New MRR | MRR from brand-new subscribers (first invoice) |
| Expansion MRR | MRR increase from upgrades or add-on additions by existing accounts |
| Contraction MRR | MRR decrease from downgrades or add-on removals by existing accounts |
| Churn MRR | MRR lost from cancelled / expired accounts |
| Net New MRR | New + Expansion ? Contraction ? Churn |
| End of Period MRR | Start MRR + Net New MRR |

---

## API Endpoints

### 1. SaaS Revenue Analytics Dashboard (Admin)

```
GET /api/v1/admin/crm/analytics/revenue
Authorization: Bearer JWT (admin_super | admin_finance)
```

**Query Parameters**

| Parameter | Type | Description |
|---|---|---|
| `period` | string | `MONTH`, `QUARTER`, `YEAR`, `CUSTOM` |
| `from` | date | Required if `period = CUSTOM` |
| `to` | date | Required if `period = CUSTOM` |
| `plan` | string | Filter by plan name |

**Response 200**
```json
{
  "success": true,
  "data": {
    "period": "MONTH",
    "reference_month": "2026-07",
    "kpi_grid": {
      "mrr_rs": 612480,
      "arr_rs": 7349760,
      "mrr_growth_pct": 8.4,
      "nrr_pct": 112.4,
      "grr_pct": 94.8,
      "quick_ratio": 3.2,
      "magic_number": 1.8,
      "ltv_cac_ratio": 5.6,
      "arpa_rs": 1024
    },
    "mrr_trend": [
      { "month": "2026-01", "mrr_rs": 410000 },
      { "month": "2026-02", "mrr_rs": 438000 },
      { "month": "2026-07", "mrr_rs": 612480 }
    ],
    "mrr_by_plan": [
      { "plan": "STARTER", "mrr_rs": 293580, "account_count": 420 },
      { "plan": "RETAIL_PRO", "mrr_rs": 269820, "account_count": 180 }
    ],
    "mrr_movement": {
      "new_mrr_rs": 38640,
      "expansion_mrr_rs": 11200,
      "contraction_mrr_rs": 3800,
      "churn_mrr_rs": 8760,
      "net_new_mrr_rs": 37280,
      "end_mrr_rs": 612480
    }
  }
}
```

---

### 2. MRR Movement Bridge (Admin)

```
GET /api/v1/admin/crm/analytics/mrr-bridge
Authorization: Bearer JWT (admin_super | admin_finance)
```

**Query Parameters**

| Parameter | Type | Description |
|---|---|---|
| `month` | string | Format `YYYY-MM`, default current month |

**Response 200**
```json
{
  "success": true,
  "data": {
    "month": "2026-07",
    "start_mrr_rs": 575200,
    "new_mrr_rs": 38640,
    "expansion_mrr_rs": 11200,
    "contraction_mrr_rs": 3800,
    "churn_mrr_rs": 8760,
    "net_new_mrr_rs": 37280,
    "end_mrr_rs": 612480,
    "new_logos": 55,
    "churned_logos": 8,
    "expansion_accounts": 22,
    "contraction_accounts": 6
  }
}
```

---

### 3. Cohort Retention Grid (Admin)

```
GET /api/v1/admin/crm/analytics/cohort
Authorization: Bearer JWT (admin_super | admin_finance)
```

**Query Parameters**

| Parameter | Type | Description |
|---|---|---|
| `cohort_from` | string | First cohort month (YYYY-MM) |
| `cohort_to` | string | Last cohort month (YYYY-MM) |

**Response 200**
```json
{
  "success": true,
  "data": {
    "cohort_retention": [
      {
        "cohort_month": "2026-01",
        "starting_accounts": 48,
        "retention_pcts": [100, 95.8, 93.7, 91.6, 89.5, 87.5, 85.4]
      },
      {
        "cohort_month": "2026-02",
        "starting_accounts": 55,
        "retention_pcts": [100, 94.5, 90.9, 89.0, 87.2, 85.4, null]
      }
    ],
    "months_since_labels": [0, 1, 2, 3, 4, 5, 6]
  }
}
```

---

### 4. Unit Economics (Admin)

```
GET /api/v1/admin/crm/analytics/unit-economics
Authorization: Bearer JWT (admin_super | admin_finance)
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "arpa_rs": 1024,
    "avg_ltv_rs": 32768,
    "avg_cac_rs": 5850,
    "ltv_cac_ratio": 5.6,
    "payback_months": 5.7,
    "gross_margin_pct": 72.0,
    "monthly_revenue_churn_pct": 1.43,
    "computed_at": "2026-07-01T00:00:00Z"
  }
}
```

---

### 5. Download Analytics Report (Admin)

```
GET /api/v1/admin/crm/analytics/report
Authorization: Bearer JWT (admin_super | admin_finance)
```

**Query Parameters**

| Parameter | Type | Description |
|---|---|---|
| `period` | string | `MONTH`, `QUARTER`, `YEAR` |
| `month` | string | `YYYY-MM` (for MONTH) |
| `format` | string | `PDF` or `CSV` |

**Response 200**
```json
{
  "success": true,
  "data": {
    "report_url": "https://cdn.nammamedmate.com/reports/saas-analytics-2026-07.pdf",
    "expires_at": "2026-07-24T11:00:00Z",
    "format": "PDF",
    "period": "2026-07"
  }
}
```

---

## Data Model

### SaaSMetricsCache (monthly snapshot)

| Field | Type | Constraints | Description |
|---|---|---|---|
| `id` | UUID v4 | PK | Record identifier |
| `metric_month` | DATE | UNIQUE, NOT NULL | First day of month |
| `mrr_rs` | DECIMAL(14,2) | NOT NULL | MRR |
| `arr_rs` | DECIMAL(14,2) | NOT NULL | ARR |
| `arpa_rs` | DECIMAL(10,2) | NOT NULL | ARPA |
| `nrr_pct` | DECIMAL(6,2) | NOT NULL | NRR % |
| `grr_pct` | DECIMAL(6,2) | NOT NULL | GRR % |
| `quick_ratio` | DECIMAL(6,2) | NOT NULL | Quick ratio |
| `magic_number` | DECIMAL(6,2) | NULLABLE | Magic number |
| `new_mrr_rs` | DECIMAL(12,2) | NOT NULL | New MRR |
| `expansion_mrr_rs` | DECIMAL(12,2) | NOT NULL | Expansion MRR |
| `contraction_mrr_rs` | DECIMAL(12,2) | NOT NULL | Contraction MRR |
| `churn_mrr_rs` | DECIMAL(12,2) | NOT NULL | Churn MRR |
| `net_new_mrr_rs` | DECIMAL(12,2) | NOT NULL | Net new MRR |
| `new_logos` | INTEGER | NOT NULL | New accounts |
| `churned_logos` | INTEGER | NOT NULL | Churned accounts |
| `computed_at` | TIMESTAMPTZ | NOT NULL | Computation time |

### CohortRetention

| Field | Type | Constraints | Description |
|---|---|---|---|
| `cohort_month` | DATE | NOT NULL | Cohort first month |
| `months_since` | INTEGER | NOT NULL | Months after acquisition |
| `starting_accounts` | INTEGER | NOT NULL | Cohort size |
| `retained_accounts` | INTEGER | NOT NULL | Still active |
| `retention_pct` | DECIMAL(5,2) | NOT NULL | Retention rate |
| PRIMARY KEY | `(cohort_month, months_since)` | | Composite |

---

## Acceptance Criteria

1. MRR on the dashboard = sum of monthly recurring revenue from all ACTIVE subscriptions and matches independent verification.
2. ARR = MRR - 12 (not sum of annual invoices).
3. NRR > 100% when expansion MRR > churn MRR; formula verified: `(MRR_end + expansion ? churn) / MRR_start - 100`.
4. GRR ? NRR always; GRR formula: `(MRR_end ? churn) / MRR_start - 100`.
5. Quick ratio > 1 when `(new + expansion) > (contraction + churn)`.
6. MRR bridge: `start_mrr + new + expansion ? contraction ? churn = end_mrr` (arithmetic integrity).
7. Cohort grid shows 100% at month 0 for all cohorts; retention values decrease or stay flat over time.
8. LTV/CAC ratio = `avg_ltv / avg_cac` and matches arithmetic.
9. Report download returns a signed URL expiring in 1 hour.
10. All endpoints return HTTP 403 for `admin_operations` role (restricted to `admin_finance` and `admin_super`).

---

## Dependencies

| Dependency | Description |
|---|---|
| Subscription Management (STORY-002) | Subscription status and plan for MRR computation |
| Billing Module (STORY-003) | Invoice amounts for revenue data |
| Renewal & Churn (STORY-007) | Churn MRR and logo churn data |
| Sales & Marketing Spend Tracking | S&M spend data for magic number and CAC |
| Scheduled Job Runner | Monthly metrics computation job |
| Report Generator | PDF/CSV report generation and CDN upload |

---

## Notes

- Magic number requires quarter-over-quarter MRR growth data and S&M spend; S&M spend must be manually entered or imported from finance systems in v1.
- Gross margin % is configured as a platform-level setting (default 72%); it can be updated by `admin_finance`.
- Cohort data is populated progressively: as each cohort ages, new retention data points are computed on the monthly run.
- All analytics endpoints are rate-limited to 10 requests per minute per admin to protect against expensive query hammering.
