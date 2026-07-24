# STORY-001: Platform Overview Analytics

| Field | Value |
|-------|-------|
| Story ID | EPIC-016-STORY-001 |
| Epic | EPIC-016 Analytics and Reporting |
| Title | Platform Overview Analytics |
| Priority | P1 |
| Status | In Development |
| Role | admin_super, admin_operations |
| Last Updated | 2026-07-24 |

## Overview

The Platform Overview Analytics story delivers the primary KPI dashboard for Admin HQ, surfacing Gross Merchandise Value (GMV), order counts, Average Order Value (AOV), net revenue, take rate, and customer engagement metrics. It supports configurable period selectors (TODAY, 7D, 30D, 90D, CUSTOM) with Week-over-Week (WoW) deltas for every KPI card. Chart data endpoints feed GMV trend lines, category mix, payment method mix, and zone-level sales bars. Leaderboard endpoints rank the top-performing pharmacies and riders, both exportable as CSV for operational reviews.

## User Roles

| Role | Access |
|------|--------|
| admin_super | Full read access to all overview analytics |
| admin_operations | Full read access to all overview analytics |
| admin_finance | Read access (GMV, revenue metrics only) |
| admin_support | No access |
| pharmacy_owner | No access (use STORY-004) |
| customer | No access |

## Business Rules

1. **Period Computation**: The `period` parameter accepts `TODAY`, `7D`, `30D`, `90D`, or `CUSTOM` (with `date_from` and `date_to`). `TODAY` returns live aggregated data from midnight UTC+5:30 to current time. All other periods are computed from midnight-to-midnight on the terminal date.
2. **WoW Delta**: WoW deltas are calculated as `(current_period_value - prior_period_value) / prior_period_value - 100`. For periods other than 7D, prior period = same-length window immediately preceding the current window.
3. **Take Rate**: `take_rate_pct = (total_commission_collected / GMV) - 100`. Commission includes platform fee plus delivery fee collected minus rider payout.
4. **Repeat Customer Rate**: `repeat_customer_pct = (customers with ? 2 orders in period / total active customers in period) - 100`. A customer is "active" if they placed at least 1 order in the period.
5. **Pre-Aggregation**: Queries for 90D+ periods must use pre-aggregated `analytics_daily_snapshots` table rather than scanning raw order tables. Pre-aggregated tables are refreshed daily at 02:00 IST.
6. **Role Enforcement**: All overview analytics endpoints require the bearer token to belong to role `admin_super` or `admin_operations`. Requests from other roles receive `403 FORBIDDEN`.
7. **Leaderboard CSV Export**: CSV export is triggered by appending `?export=csv` to the leaderboard endpoint. Exported file is stored in AWS S3 and a pre-signed URL is returned (expires 1 hour).
8. **Net Revenue vs GMV**: `net_revenue = GMV - refunds - cancellations`. `net_margin_pct = (net_revenue - cogs_estimate) / net_revenue - 100`.

## API Endpoints

### GET /api/v1/admin/analytics/overview

Retrieve KPI cards for the platform overview dashboard.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Query Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| period | string | Yes | `TODAY`, `7D`, `30D`, `90D`, `CUSTOM` |
| date_from | string | No | ISO 8601 date; required when period=CUSTOM |
| date_to | string | No | ISO 8601 date; required when period=CUSTOM |

**Response 200**
```json
{
  "success": true,
  "data": {
    "period": "7D",
    "date_from": "2026-07-17T00:00:00Z",
    "date_to": "2026-07-24T23:59:59Z",
    "kpis": {
      "gmv": {
        "value": 4820000,
        "unit": "paise",
        "wow_delta_pct": 12.4
      },
      "orders_count": {
        "value": 3841,
        "wow_delta_pct": 8.2
      },
      "aov": {
        "value": 125500,
        "unit": "paise",
        "wow_delta_pct": 3.9
      },
      "net_revenue": {
        "value": 4580000,
        "unit": "paise",
        "wow_delta_pct": 11.1
      },
      "net_margin_pct": {
        "value": 18.4,
        "wow_delta_pct": -0.6
      },
      "take_rate_pct": {
        "value": 14.2,
        "wow_delta_pct": 0.3
      },
      "active_customers": {
        "value": 2140,
        "wow_delta_pct": 5.8
      },
      "repeat_customer_pct": {
        "value": 38.5,
        "wow_delta_pct": 2.1
      }
    },
    "generated_at": "2026-07-24T01:11:00Z",
    "data_source": "LIVE"
  },
  "meta": {}
}
```

**Error Table**

| HTTP Code | Error Code | Condition |
|-----------|-----------|-----------|
| 400 | INVALID_PERIOD | period not in allowed set |
| 400 | MISSING_DATE_RANGE | CUSTOM period without date_from/date_to |
| 403 | FORBIDDEN | Insufficient role |
| 422 | DATE_RANGE_TOO_LARGE | Custom range exceeds 365 days |

---

### GET /api/v1/admin/analytics/overview/charts

Retrieve chart-ready data: GMV trend, category mix, payment mix, and sales by zone.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Query Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| period | string | Yes | `TODAY`, `7D`, `30D`, `90D`, `CUSTOM` |
| date_from | string | No | Required when period=CUSTOM |
| date_to | string | No | Required when period=CUSTOM |

**Response 200**
```json
{
  "success": true,
  "data": {
    "gmv_trend": [
      { "date": "2026-07-17", "gmv_paise": 612000 },
      { "date": "2026-07-18", "gmv_paise": 698000 },
      { "date": "2026-07-24", "gmv_paise": 814000 }
    ],
    "category_mix": [
      { "category": "OTC_MEDICINES", "gmv_paise": 1920000, "pct": 39.8 },
      { "category": "PRESCRIPTION_MEDICINES", "gmv_paise": 1540000, "pct": 31.9 },
      { "category": "WELLNESS_SUPPLEMENTS", "gmv_paise": 820000, "pct": 17.0 },
      { "category": "DEVICES_EQUIPMENT", "gmv_paise": 540000, "pct": 11.2 }
    ],
    "payment_mix": [
      { "method": "UPI", "orders": 1920, "pct": 50.0 },
      { "method": "CARD", "orders": 960, "pct": 25.0 },
      { "method": "COD", "orders": 576, "pct": 15.0 },
      { "method": "WALLET", "orders": 385, "pct": 10.0 }
    ],
    "sales_by_zone": [
      { "zone_id": "uuid-zone-1", "zone_name": "Indiranagar", "gmv_paise": 980000, "orders": 820 },
      { "zone_id": "uuid-zone-2", "zone_name": "Koramangala", "gmv_paise": 870000, "orders": 710 }
    ]
  },
  "meta": {}
}
```

**Error Table**

| HTTP Code | Error Code | Condition |
|-----------|-----------|-----------|
| 400 | INVALID_PERIOD | Invalid period value |
| 403 | FORBIDDEN | Insufficient role |

---

### GET /api/v1/admin/analytics/overview/leaderboards

Retrieve top pharmacy and top rider leaderboards.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Query Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| period | string | Yes | `7D`, `30D`, `90D` |
| top_n | integer | No | Number of leaders to return, default 10, max 50 |
| export | string | No | `csv` - triggers CSV export |

**Response 200**
```json
{
  "success": true,
  "data": {
    "top_pharmacies": [
      {
        "rank": 1,
        "pharmacy_id": "uuid-ph-1",
        "name": "Apollo Pharmacy - Indiranagar",
        "area": "Indiranagar",
        "rating": 4.8,
        "orders": 412,
        "gmv_paise": 620000,
        "fill_rate_pct": 96.2
      }
    ],
    "top_riders": [
      {
        "rank": 1,
        "rider_id": "uuid-r-1",
        "name": "Ramesh Kumar",
        "zone": "Indiranagar",
        "trips": 184,
        "on_time_pct": 97.8,
        "rating": 4.9,
        "earnings_paise": 28400
      }
    ],
    "export_url": null
  },
  "meta": {}
}
```

When `?export=csv` is set, `export_url` will contain an S3 pre-signed URL.

**Error Table**

| HTTP Code | Error Code | Condition |
|-----------|-----------|-----------|
| 400 | INVALID_PERIOD | TODAY not supported for leaderboards |
| 403 | FORBIDDEN | Insufficient role |
| 422 | EXPORT_TOO_LARGE | top_n > 50 with export=csv |

---

## Data Models

### analytics_daily_snapshots

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| snapshot_date | DATE | Aggregation date |
| gmv_paise | BIGINT | Total GMV for the day |
| orders_count | INTEGER | Total orders placed |
| delivered_count | INTEGER | Total orders delivered |
| cancelled_count | INTEGER | Total orders cancelled |
| net_revenue_paise | BIGINT | GMV minus refunds |
| commission_paise | BIGINT | Platform commission collected |
| active_customers | INTEGER | Unique customers with ?1 order |
| repeat_customers | INTEGER | Customers with ?2 orders |
| new_customers | INTEGER | First-time customers |
| zone_id | UUID | FK ? zones (nullable; null = platform-wide) |
| created_at | TIMESTAMPTZ | Row creation time |

### analytics_payment_mix_daily

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| snapshot_date | DATE | Aggregation date |
| payment_method | VARCHAR(30) | UPI, CARD, COD, WALLET |
| orders_count | INTEGER | Orders via this method |
| gmv_paise | BIGINT | GMV via this method |

### analytics_category_mix_daily

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| snapshot_date | DATE | Aggregation date |
| category | VARCHAR(50) | Product category |
| gmv_paise | BIGINT | Category GMV |
| units_sold | INTEGER | Units sold |

## Acceptance Criteria

1. **AC-001**: GET /overview with period=7D returns 8 KPI cards (gmv, orders_count, aov, net_revenue, net_margin_pct, take_rate_pct, active_customers, repeat_customer_pct) each with a `wow_delta_pct`.
2. **AC-002**: GET /overview with period=TODAY returns `data_source: "LIVE"` and computes metrics from current-day orders.
3. **AC-003**: GET /overview with period=90D returns in under 2 seconds (uses pre-aggregated tables).
4. **AC-004**: GET /charts returns a `gmv_trend` array with one data point per day in the period.
5. **AC-005**: GET /leaderboards with `?export=csv` returns a non-null `export_url` (S3 pre-signed link valid for 1 hour).
6. **AC-006**: Requests from role `admin_support` to any overview endpoint return `403 FORBIDDEN`.
7. **AC-007**: CUSTOM period without `date_from` or `date_to` returns `400 MISSING_DATE_RANGE`.
8. **AC-008**: `take_rate_pct` value matches formula `(commission / GMV) - 100` within 0.01% tolerance.

## Dependencies

| Dependency | Type | Notes |
|-----------|------|-------|
| EPIC-001 Order Management | Data source | Order records with status, amount, category |
| EPIC-005 Finance | Data source | Commission, payout ledger |
| Pre-aggregation batch job | Infrastructure | Runs nightly at 02:00 IST |
| AWS S3 | Storage | CSV export uploads |
| EPIC-007 Rider Management | Data source | Rider leaderboard data |
| EPIC-006 Pharmacy | Data source | Pharmacy leaderboard data |

## Notes

- All monetary values in the API are returned in **paise** (1 Rs = 100 paise). Frontend divides by 100 for display.
- `data_source` field indicates `LIVE` (real-time query) or `AGGREGATED` (pre-computed snapshot). Frontend can display this to indicate data freshness.
- WoW delta for TODAY period uses same calendar day of the previous week as prior period.
- Leaderboard ties (same GMV/trips) are broken by ascending pharmacy/rider name alphabetically.
