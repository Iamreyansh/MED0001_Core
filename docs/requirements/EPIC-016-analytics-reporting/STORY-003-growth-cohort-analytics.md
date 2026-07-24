# STORY-003: Growth & Cohort Analytics

| Field | Value |
|-------|-------|
| Story ID | EPIC-016-STORY-003 |
| Epic | EPIC-016 Analytics and Reporting |
| Title | Growth & Cohort Analytics |
| Priority | P1 |
| Status | In Development |
| Role | admin_super, admin_operations |
| Last Updated | 2026-07-24 |

## Overview

The Growth & Cohort Analytics story gives the Admin HQ Growth team visibility into customer acquisition channels, retention curves, and order volume trends. A weekly retention cohort heatmap (12-cohort - 13-week matrix) allows the team to assess whether recent cohorts retain at a higher rate than historical ones. The acquisition source mix endpoint breaks down new-user volume, orders, GMV, and cost-per-acquisition by source. The order-trend endpoint splits daily/weekly volume into new vs. returning customers, powering the growth marketing dashboard.

## User Roles

| Role | Access |
|------|--------|
| admin_super | Full access |
| admin_operations | Full access |
| admin_finance | Read GMV and acquisition cost metrics only |
| admin_support | No access |
| pharmacy_owner | No access |
| customer | No access |

## Business Rules

1. **Cohort Definition**: A customer's cohort is determined by the ISO calendar week of their very first order. Cohort membership never changes even if the customer goes dormant and returns.
2. **Retention Calculation**: For cohort week W and elapsed weeks N: `retention_pct = (customers in cohort W who placed ?1 order in week W+N) / (cohort_size_W) - 100`. Week 0 is always 100%.
3. **Month-1 Retention**: `month1_retention_pct = week-4 retention of the most recent complete monthly cohort`. A monthly cohort is formed from the first week of each calendar month.
4. **Acquisition Source Tracking**: Source is derived from UTM parameters captured at app install (first_open event from Firebase). Valid sources: `ORGANIC` (no UTM), `REFERRAL` (referral code present), `AD` (UTM source = google/meta/other paid), `PARTNER` (co-marketing partner code).
5. **CAC Calculation**: `cac_rs = total_ad_spend_for_source / new_users_from_source`. Ad spend is input manually by the Growth team via a separate spend-entry form; the analytics endpoint consumes the `campaign_spend` table.
6. **Cohort Table Pre-computation**: The retention cohort table is pre-computed weekly (every Sunday at 03:00 IST) and stored in `analytics_cohort_retention`. Live computation is not supported for performance reasons.
7. **New vs. Returning Classification**: `new_customer` = placing their very first order on the platform (lifetime). `returning_customer` = any customer who has placed ?1 order prior to the current order date.
8. **Heatmap Color Coding**: The API returns raw `retention_pct` values 0-100. Frontend applies color gradients: 0-20% red, 21-50% amber, 51-75% green, 76-100% dark green. This mapping is a front-end concern only.

## API Endpoints

### GET /api/v1/admin/analytics/growth

Retrieve growth KPI cards.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Query Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| period | string | Yes | `7D`, `30D`, `90D`, `CUSTOM` |
| date_from | string | No | Required when CUSTOM |
| date_to | string | No | Required when CUSTOM |

**Response 200**
```json
{
  "success": true,
  "data": {
    "period": "30D",
    "kpis": {
      "active_customers": {
        "value": 4820,
        "wow_delta_pct": 6.4
      },
      "new_customers": {
        "value": 812,
        "wow_delta_pct": 12.1
      },
      "repeat_rate_pct": {
        "value": 41.2,
        "wow_delta_pct": 1.8
      },
      "month1_retention_pct": {
        "value": 38.5,
        "cohort_week": "2026-W24",
        "wow_delta_pct": -2.3
      }
    },
    "generated_at": "2026-07-24T01:20:00Z"
  },
  "meta": {}
}
```

**Error Table**

| HTTP Code | Error Code | Condition |
|-----------|-----------|-----------|
| 400 | INVALID_PERIOD | Period not in allowed set |
| 403 | FORBIDDEN | Insufficient role |

---

### GET /api/v1/admin/analytics/growth/cohort

Retrieve the weekly retention cohort heatmap (last 12 cohorts, 13 weekly columns).

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Query Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| cohort_count | integer | No | Number of cohorts to return; default 12, max 26 |

**Response 200**
```json
{
  "success": true,
  "data": {
    "weeks_header": [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12],
    "cohorts": [
      {
        "cohort_week": "2026-W17",
        "cohort_size": 284,
        "retention_pcts": [100, 48.2, 38.4, 30.6, 27.8, 24.3, 22.1, 20.5, 19.8, 18.2, null, null, null]
      },
      {
        "cohort_week": "2026-W16",
        "cohort_size": 318,
        "retention_pcts": [100, 51.3, 40.1, 32.7, 29.5, 26.8, 24.2, 22.0, 20.1, 19.5, 17.8, 16.4, null]
      }
    ],
    "last_computed_at": "2026-07-20T03:14:00Z"
  },
  "meta": {}
}
```

Null values indicate the cohort has not yet reached that elapsed week.

**Error Table**

| HTTP Code | Error Code | Condition |
|-----------|-----------|-----------|
| 403 | FORBIDDEN | Insufficient role |
| 422 | COHORT_COUNT_TOO_LARGE | cohort_count > 26 |

---

### GET /api/v1/admin/analytics/growth/acquisition

Retrieve acquisition source breakdown.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Query Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| period | string | Yes | `7D`, `30D`, `90D`, `CUSTOM` |
| date_from | string | No | Required when CUSTOM |
| date_to | string | No | Required when CUSTOM |

**Response 200**
```json
{
  "success": true,
  "data": {
    "period": "30D",
    "total_new_users": 812,
    "sources": [
      {
        "source": "ORGANIC",
        "new_users": 412,
        "pct": 50.7,
        "orders": 680,
        "gmv_paise": 842000,
        "cac_rs": 0
      },
      {
        "source": "REFERRAL",
        "new_users": 198,
        "pct": 24.4,
        "orders": 312,
        "gmv_paise": 384000,
        "cac_rs": 42
      },
      {
        "source": "AD",
        "new_users": 152,
        "pct": 18.7,
        "orders": 220,
        "gmv_paise": 278000,
        "cac_rs": 180
      },
      {
        "source": "PARTNER",
        "new_users": 50,
        "pct": 6.2,
        "orders": 74,
        "gmv_paise": 96000,
        "cac_rs": 95
      }
    ]
  },
  "meta": {}
}
```

**Error Table**

| HTTP Code | Error Code | Condition |
|-----------|-----------|-----------|
| 400 | INVALID_PERIOD | Invalid period |
| 403 | FORBIDDEN | Insufficient role |

---

### GET /api/v1/admin/analytics/growth/order-trend

Retrieve daily/weekly order volume with new vs. returning customer split.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Query Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| period | string | Yes | `7D`, `30D`, `90D` |
| granularity | string | No | `DAILY` (default) or `WEEKLY` |

**Response 200**
```json
{
  "success": true,
  "data": {
    "period": "30D",
    "granularity": "DAILY",
    "trend": [
      {
        "date": "2026-06-24",
        "total_orders": 118,
        "new_customer_orders": 28,
        "returning_customer_orders": 90
      },
      {
        "date": "2026-06-25",
        "total_orders": 132,
        "new_customer_orders": 31,
        "returning_customer_orders": 101
      }
    ]
  },
  "meta": {}
}
```

**Error Table**

| HTTP Code | Error Code | Condition |
|-----------|-----------|-----------|
| 400 | INVALID_PERIOD | Invalid period |
| 400 | INVALID_GRANULARITY | Granularity not DAILY or WEEKLY |
| 403 | FORBIDDEN | Insufficient role |

---

## Data Models

### analytics_cohort_retention

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| cohort_week | VARCHAR(8) | ISO week e.g. `2026-W17` |
| cohort_size | INTEGER | First-order customers in that week |
| elapsed_week | INTEGER | 0-52 |
| retained_count | INTEGER | Customers who ordered in cohort_week + elapsed_week |
| retention_pct | DECIMAL(5,2) | Pre-computed percentage |
| computed_at | TIMESTAMPTZ | Last computation time |

### analytics_acquisition_daily

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| snapshot_date | DATE | |
| source | VARCHAR(20) | ORGANIC, REFERRAL, AD, PARTNER |
| new_users | INTEGER | |
| orders | INTEGER | |
| gmv_paise | BIGINT | |

### campaign_spend

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| source | VARCHAR(20) | AD, PARTNER, REFERRAL |
| spend_rs | DECIMAL(12,2) | Total spend |
| period_from | DATE | |
| period_to | DATE | |
| entered_by | UUID | FK ? admin_users |
| created_at | TIMESTAMPTZ | |

## Acceptance Criteria

1. **AC-001**: GET /growth returns `month1_retention_pct` with its source `cohort_week` so the admin can verify which cohort was used.
2. **AC-002**: GET /cohort returns a matrix where row 0 (Week 0) is always 100% for all cohorts.
3. **AC-003**: GET /cohort returns null for elapsed weeks that are in the future (cohort has not yet reached that week).
4. **AC-004**: GET /acquisition returns percentages that sum to 100% across all sources.
5. **AC-005**: GET /acquisition returns `cac_rs: 0` for ORGANIC source (no ad spend attributable).
6. **AC-006**: GET /order-trend with granularity=WEEKLY returns one data point per ISO week in the period.
7. **AC-007**: `new_customer_orders` + `returning_customer_orders` equals `total_orders` for every data point in the trend.
8. **AC-008**: Cohort table is pre-computed; the endpoint returns stale `last_computed_at` timestamp rather than computing live, and live computation is never triggered by this endpoint.

## Dependencies

| Dependency | Type | Notes |
|-----------|------|-------|
| EPIC-001 Order Management | Data source | First-order dates for cohort computation |
| Firebase Analytics | Data source | Install source / UTM tracking |
| Campaign spend entry form | Internal tool | Manual spend input for CAC |
| Weekly cohort batch job | Infrastructure | Runs Sunday 03:00 IST |
| EPIC-016-STORY-001 | Analytics foundation | Pre-aggregation patterns |

## Notes

- Cohort computation is intentionally weekly (not daily) to produce statistically meaningful cohort sizes. Daily cohorts would have too few customers per cohort for reliable percentages.
- The `CUSTOM` period is not supported for the cohort endpoint (cohort boundaries are fixed weekly). Only `cohort_count` parameter is accepted.
- Future: LTV (Lifetime Value) per cohort can be added as an additional column once sufficient data is available.
