# STORY-005: Geography Analytics

| Field | Value |
|-------|-------|
| Story ID | EPIC-016-STORY-005 |
| Epic | EPIC-016 Analytics and Reporting |
| Title | Geography Analytics |
| Priority | P1 |
| Status | In Development |
| Role | admin_super, admin_operations |
| Last Updated | 2026-07-24 |

## Overview

The Geography Analytics story surfaces zone-level performance metrics, supply-demand gap analysis, and hourly demand heatmap data to enable the logistics team to make data-driven decisions about rider allocation and pharmacy coverage. GMV, order counts, AOV, SLA adherence, and delivery times are presented per delivery zone, sorted by GMV contribution. The supply-gap endpoint classifies each zone by gap severity and recommends actions. The demand heatmap provides hour-of-day averages per zone to identify peak windows and inform rider scheduling.

## User Roles

| Role | Access |
|------|--------|
| admin_super | Full access |
| admin_operations | Full access |
| admin_finance | Read zone GMV only |
| admin_support | No access |
| pharmacy_owner | No access |
| customer | No access |

## Business Rules

1. **Zone Scope**: Every metric is tied to the delivery zone of the order's delivery address. If a delivery address falls in multiple overlapping zones (e.g., sub-zone within macro-zone), the most specific (innermost) zone is used.
2. **Supply-Demand Scores**: `demand_score` = average hourly order count in the zone for the selected period. `supply_score` = average available riders - average pharmacy operational coverage (0-1) for the zone during business hours.
3. **Gap Severity Classification**: Gap = `(demand_score - supply_score) / demand_score`. Severity: `CRITICAL` (gap > 50%), `HIGH` (30-50%), `MODERATE` (10-30%), `LOW` (< 10%). Zones where supply > demand are classified as `LOW` (surplus).
4. **Suggestions**: `ADD_RIDERS` when gap is CRITICAL/HIGH and pharmacy coverage is adequate (> 80%). `ADD_PHARMACIES` when pharmacy coverage < 60% regardless of rider count. `EXPAND_ZONE` when the surrounding area has unserved order attempts (failed geocode ? zone mapping).
5. **Demand Heatmap Granularity**: Hourly demand is computed as an average over the rolling 28-day window, broken down by `hour_of_day` (0-23) and by `day_of_week`. The endpoint returns `avg_orders` and `peak_day` per hour.
6. **Sorting Default**: GET /geography returns zones sorted by GMV descending. Sortable by `orders`, `sla_adherence_pct`, `avg_delivery_minutes`.
7. **Dark Zone Definition**: A zone with `riders_online = 0` for the current snapshot is flagged as `dark`. Dark zones are surfaced as CRITICAL in the supply-gap endpoint and trigger an alert in EPIC-020.
8. **Period Constraint**: Geography analytics support `TODAY`, `7D`, `30D`. Periods > 30D are not supported as zone boundaries may have changed (zone editing history is not tracked in V1).

## API Endpoints

### GET /api/v1/admin/analytics/geography

Retrieve zone-level GMV, orders, AOV, pharmacy count, rider count, and SLA performance.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Query Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| period | string | Yes | `TODAY`, `7D`, `30D` |
| sort | string | No | `gmv`, `orders`, `sla_adherence_pct`, `avg_delivery_minutes` (default: `gmv`) |
| order | string | No | `asc`, `desc` (default: `desc`) |

**Response 200**
```json
{
  "success": true,
  "data": {
    "period": "7D",
    "zones": [
      {
        "zone_id": "uuid-zone-1",
        "zone_name": "Indiranagar",
        "gmv_paise": 980000,
        "orders": 820,
        "aov_paise": 119512,
        "pharmacies_count": 8,
        "riders_online": 12,
        "sla_adherence_pct": 95.1,
        "avg_delivery_minutes": 28.4,
        "is_dark": false
      },
      {
        "zone_id": "uuid-zone-4",
        "zone_name": "Whitefield",
        "gmv_paise": 312000,
        "orders": 280,
        "aov_paise": 114285,
        "pharmacies_count": 3,
        "riders_online": 0,
        "sla_adherence_pct": 61.2,
        "avg_delivery_minutes": 68.4,
        "is_dark": true
      }
    ],
    "total_zones": 12,
    "dark_zones_count": 1
  },
  "meta": {}
}
```

**Error Table**

| HTTP Code | Error Code | Condition |
|-----------|-----------|-----------|
| 400 | INVALID_PERIOD | Period > 30D or invalid |
| 403 | FORBIDDEN | Insufficient role |

---

### GET /api/v1/admin/analytics/geography/supply-gap

Retrieve zone-level supply-demand gap analysis with severity and remediation suggestions.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Query Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| period | string | Yes | `7D`, `30D` |
| severity | string | No | Filter: `CRITICAL`, `HIGH`, `MODERATE`, `LOW` |

**Response 200**
```json
{
  "success": true,
  "data": {
    "period": "7D",
    "summary": {
      "critical_zones": 1,
      "high_zones": 2,
      "moderate_zones": 4,
      "low_zones": 5
    },
    "zones": [
      {
        "zone_id": "uuid-zone-4",
        "zone_name": "Whitefield",
        "demand_score": 8.4,
        "supply_score": 2.1,
        "gap_pct": 75.0,
        "gap_severity": "CRITICAL",
        "pharmacy_coverage_pct": 88.0,
        "suggestion": "ADD_RIDERS",
        "current_riders_avg": 1.2,
        "current_pharmacies": 3
      },
      {
        "zone_id": "uuid-zone-5",
        "zone_name": "Electronic City",
        "demand_score": 5.2,
        "supply_score": 3.1,
        "gap_pct": 40.4,
        "gap_severity": "HIGH",
        "pharmacy_coverage_pct": 45.0,
        "suggestion": "ADD_PHARMACIES",
        "current_riders_avg": 3.8,
        "current_pharmacies": 2
      }
    ]
  },
  "meta": {}
}
```

**Error Table**

| HTTP Code | Error Code | Condition |
|-----------|-----------|-----------|
| 400 | INVALID_PERIOD | Period not in 7D/30D |
| 403 | FORBIDDEN | Insufficient role |

---

### GET /api/v1/admin/analytics/geography/demand-heatmap

Retrieve hourly average order demand per zone for heatmap charting.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Query Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| zone_id | UUID | No | Filter to single zone; returns all zones if omitted |

**Response 200**
```json
{
  "success": true,
  "data": {
    "computed_over_days": 28,
    "zones": [
      {
        "zone_id": "uuid-zone-1",
        "zone_name": "Indiranagar",
        "hourly": [
          { "hour_of_day": 0,  "avg_orders": 0.1, "peak_day": null },
          { "hour_of_day": 7,  "avg_orders": 4.8, "peak_day": "MONDAY" },
          { "hour_of_day": 8,  "avg_orders": 8.2, "peak_day": "MONDAY" },
          { "hour_of_day": 12, "avg_orders": 9.4, "peak_day": "SUNDAY" },
          { "hour_of_day": 20, "avg_orders": 11.2,"peak_day": "SATURDAY" },
          { "hour_of_day": 23, "avg_orders": 1.4, "peak_day": null }
        ]
      }
    ]
  },
  "meta": {}
}
```

**Error Table**

| HTTP Code | Error Code | Condition |
|-----------|-----------|-----------|
| 403 | FORBIDDEN | Insufficient role |
| 404 | ZONE_NOT_FOUND | zone_id not found |

---

## Data Models

### analytics_zone_daily

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| zone_id | UUID | FK ? zones |
| snapshot_date | DATE | Aggregation date |
| gmv_paise | BIGINT | |
| orders_count | INTEGER | |
| sla_breached_count | INTEGER | |
| total_delivery_seconds | BIGINT | Sum for avg computation |
| avg_riders_online | DECIMAL(5,2) | Avg riders online during business hours |
| pharmacies_count | INTEGER | Active pharmacies in zone |

### analytics_zone_hourly_demand

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| zone_id | UUID | FK ? zones |
| hour_of_day | SMALLINT | 0-23 |
| day_of_week | SMALLINT | 0=Sunday, 6=Saturday |
| avg_orders | DECIMAL(6,2) | Rolling 28-day average |
| computed_at | TIMESTAMPTZ | Last computation |

### zones

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| name | VARCHAR(100) | Zone display name |
| city_id | UUID | FK ? cities |
| boundary_geojson | JSONB | GeoJSON Polygon for zone boundary |
| sla_threshold_minutes | INTEGER | Default 45 |
| is_active | BOOLEAN | |
| created_at | TIMESTAMPTZ | |

## Acceptance Criteria

1. **AC-001**: GET /geography returns zones sorted by GMV descending by default; `is_dark: true` for zones with `riders_online = 0`.
2. **AC-002**: GET /supply-gap classifies zones correctly: gap > 50% = CRITICAL, 30-50% = HIGH, 10-30% = MODERATE, < 10% = LOW.
3. **AC-003**: GET /supply-gap suggests `ADD_RIDERS` when gap is CRITICAL and pharmacy coverage > 80%; `ADD_PHARMACIES` when pharmacy coverage < 60%.
4. **AC-004**: GET /demand-heatmap returns 24 hour entries (0-23) per zone.
5. **AC-005**: GET /geography with period > 30D returns `400 INVALID_PERIOD`.
6. **AC-006**: Dark zones (riders_online=0) in GET /supply-gap appear with `gap_severity: CRITICAL` regardless of demand_score.
7. **AC-007**: GET /geography `dark_zones_count` matches the count of zones with `is_dark: true` in the zones array.
8. **AC-008**: Demand heatmap data is pre-computed over last 28 days; calling the endpoint never triggers a live scan of raw order data.

## Dependencies

| Dependency | Type | Notes |
|-----------|------|-------|
| EPIC-001 Order Management | Data source | Order delivery zone |
| EPIC-004 Dispatch | Data source | Rider online status per zone |
| EPIC-006 Pharmacy | Data source | Pharmacy operational hours |
| Zone configuration | Config | Zone boundaries (GeoJSON) |
| EPIC-020 Observability | Consumer | Dark zone alert integration |
| Nightly batch job | Infrastructure | Demand heatmap recomputation |

## Notes

- `riders_online` in the zone overview is the live count at time of request (real-time query), not a historical aggregate.
- Future: Add a map visualization layer that renders zone boundaries with color-coded gap severity; the heatmap and gap API are designed to support this.
- Zone boundary changes are not versioned in V1; queries over periods where a zone boundary changed may have slight inaccuracy.
