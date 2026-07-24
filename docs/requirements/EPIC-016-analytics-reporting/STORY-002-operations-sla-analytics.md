# STORY-002: Operations & SLA Analytics

| Field | Value |
|-------|-------|
| Story ID | EPIC-016-STORY-002 |
| Epic | EPIC-016 Analytics and Reporting |
| Title | Operations & SLA Analytics |
| Priority | P1 |
| Status | In Development |
| Role | admin_super, admin_operations |
| Last Updated | 2026-07-24 |

## Overview

The Operations & SLA Analytics story provides fulfilment-focused metrics for the Admin Command Center, including SLA adherence, fill rate, average prep time, delivery times, and live order count. It exposes a multi-stage fulfilment funnel to identify where orders are being lost. Delivery time breakdown endpoints provide P50/P90 percentile data segmented by delivery zone. Cancellation analytics surface reasons, stages, and per-pharmacy/per-zone distributions to drive quality improvement. All ops analytics power the live Admin Command Center view and feed into automated remediation workflows.

## User Roles

| Role | Access |
|------|--------|
| admin_super | Full access |
| admin_operations | Full access |
| admin_finance | Read KPIs only |
| admin_support | Read KPIs only |
| pharmacy_owner | No access (use STORY-004) |
| customer | No access |

## Business Rules

1. **SLA Adherence**: `sla_adherence_pct = (orders delivered within SLA threshold / total delivered orders in period) - 100`. Default SLA threshold is 45 minutes from order placement; configurable per zone.
2. **Fill Rate**: `fill_rate_pct = (orders fulfilled by pharmacy / orders received by pharmacies) - 100`. An order is "fulfilled" when it reaches `PACKED` status. Orders cancelled before acceptance are excluded from denominator.
3. **Funnel Drop-off**: Each funnel stage shows the absolute count and the drop-off percentage from the previous stage. Stage sequence: `orders_placed ? accepted ? packed ? out_for_delivery ? delivered`.
4. **Delivery Time Percentiles**: P50 and P90 percentiles are computed server-side using a window function over the period's delivery time distribution. Times are split into: `pharmacy_prep_minutes` (placement ? packed), `rider_pickup_minutes` (assigned ? picked up), `delivery_minutes` (pickup ? delivered).
5. **Cancellation Stage Classification**: Pre-accept cancellations = cancelled before pharmacy accepts. Post-accept cancellations = cancelled after acceptance. Cancellation reason categories: `customer` (changed_mind, wrong_address, duplicate_order), `pharmacy` (out_of_stock, closing_soon, incomplete_prescription), `system` (no_rider_available, payment_failed, timeout).
6. **Live Orders Count**: `live_orders_now` is a real-time count of orders in non-terminal statuses (`PLACED`, `ACCEPTED`, `PACKED`, `OUT_FOR_DELIVERY`). It is refreshed every 30 seconds for the Admin Command Center.
7. **Zone Segmentation**: Delivery breakdown and cancellation endpoints accept an optional `zone_id` filter. Omitting it returns aggregated data across all zones.
8. **Data Freshness**: Operations analytics for periods other than TODAY are refreshed from pre-aggregated tables updated every 15 minutes during business hours (06:00-23:00 IST).

## API Endpoints

### GET /api/v1/admin/analytics/operations

Retrieve operations KPI cards.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Query Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| period | string | Yes | `TODAY`, `7D`, `30D`, `90D`, `CUSTOM` |
| date_from | string | No | ISO 8601; required when CUSTOM |
| date_to | string | No | ISO 8601; required when CUSTOM |
| zone_id | UUID | No | Filter to a single zone |

**Response 200**
```json
{
  "success": true,
  "data": {
    "period": "7D",
    "kpis": {
      "sla_adherence_pct": { "value": 92.4, "wow_delta_pct": -1.2 },
      "fill_rate_pct": { "value": 94.8, "wow_delta_pct": 0.5 },
      "avg_prep_minutes": { "value": 8.2, "wow_delta_pct": -0.4 },
      "avg_delivery_minutes": { "value": 34.6, "wow_delta_pct": 2.1 },
      "cancel_rate_pct": { "value": 4.1, "wow_delta_pct": 0.3 },
      "live_orders_now": { "value": 47, "unit": "orders" }
    },
    "generated_at": "2026-07-24T01:15:00Z"
  },
  "meta": {}
}
```

**Error Table**

| HTTP Code | Error Code | Condition |
|-----------|-----------|-----------|
| 400 | INVALID_PERIOD | Period not in allowed set |
| 403 | FORBIDDEN | Insufficient role |
| 404 | ZONE_NOT_FOUND | zone_id does not exist |

---

### GET /api/v1/admin/analytics/operations/fulfilment-funnel

Retrieve the order fulfilment funnel with stage counts and drop-off rates.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Query Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| period | string | Yes | Period selector |
| zone_id | UUID | No | Filter by zone |

**Response 200**
```json
{
  "success": true,
  "data": {
    "period": "7D",
    "funnel": [
      { "stage": "orders_placed",       "count": 3841, "drop_off_pct": null },
      { "stage": "accepted",            "count": 3726, "drop_off_pct": 3.0 },
      { "stage": "packed",              "count": 3534, "drop_off_pct": 5.2 },
      { "stage": "out_for_delivery",    "count": 3498, "drop_off_pct": 1.0 },
      { "stage": "delivered",           "count": 3420, "drop_off_pct": 2.2 }
    ],
    "overall_completion_rate_pct": 89.0
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

### GET /api/v1/admin/analytics/operations/delivery-breakdown

Retrieve P50/P90 delivery time breakdown segmented by zone.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Query Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| period | string | Yes | Period selector |

**Response 200**
```json
{
  "success": true,
  "data": {
    "period": "7D",
    "platform_aggregate": {
      "pharmacy_prep_minutes": { "p50": 7.5, "p90": 14.2 },
      "rider_pickup_minutes": { "p50": 5.1, "p90": 10.8 },
      "delivery_minutes":     { "p50": 18.4, "p90": 31.6 },
      "total_minutes":        { "p50": 31.0, "p90": 56.6 }
    },
    "by_zone": [
      {
        "zone_id": "uuid-zone-1",
        "zone_name": "Indiranagar",
        "pharmacy_prep_minutes": { "p50": 6.8, "p90": 12.4 },
        "rider_pickup_minutes":  { "p50": 4.9, "p90": 9.8 },
        "delivery_minutes":      { "p50": 17.2, "p90": 28.9 },
        "total_minutes":         { "p50": 28.9, "p90": 51.1 },
        "sla_adherence_pct": 95.1
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

### GET /api/v1/admin/analytics/operations/cancellations

Retrieve cancellation analysis by reason, stage, pharmacy, and zone.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Query Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| period | string | Yes | Period selector |
| zone_id | UUID | No | Filter by zone |

**Response 200**
```json
{
  "success": true,
  "data": {
    "period": "7D",
    "summary": {
      "total_cancellations": 421,
      "cancel_rate_pct": 4.1
    },
    "by_stage": {
      "pre_accept": { "count": 198, "pct": 47.0 },
      "post_accept": { "count": 223, "pct": 53.0 }
    },
    "by_reason": [
      { "reason": "out_of_stock",        "actor": "pharmacy", "count": 142, "pct": 33.7 },
      { "reason": "changed_mind",        "actor": "customer", "count": 112, "pct": 26.6 },
      { "reason": "no_rider_available",  "actor": "system",   "count": 78,  "pct": 18.5 },
      { "reason": "wrong_address",       "actor": "customer", "count": 45,  "pct": 10.7 },
      { "reason": "payment_failed",      "actor": "system",   "count": 28,  "pct": 6.6 },
      { "reason": "closing_soon",        "actor": "pharmacy", "count": 16,  "pct": 3.8 }
    ],
    "top_pharmacies_by_cancellation": [
      { "pharmacy_id": "uuid-ph-5", "name": "Medplus - HSR Layout", "cancellations": 38, "cancel_rate_pct": 12.4 }
    ],
    "by_zone": [
      { "zone_id": "uuid-zone-3", "zone_name": "Whitefield", "cancellations": 89, "cancel_rate_pct": 7.8 }
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

## Data Models

### analytics_ops_snapshots

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| snapshot_date | DATE | Aggregation date |
| zone_id | UUID | FK ? zones (null = platform-wide) |
| sla_threshold_minutes | INTEGER | SLA threshold at time of snapshot |
| orders_placed | INTEGER | Total orders placed |
| orders_accepted | INTEGER | Orders accepted by pharmacy |
| orders_packed | INTEGER | Orders packed |
| orders_out_for_delivery | INTEGER | Out for delivery |
| orders_delivered | INTEGER | Delivered |
| orders_cancelled | INTEGER | Cancelled |
| sla_breached_count | INTEGER | Orders delivered after SLA |
| total_prep_seconds | BIGINT | Sum of prep durations (for avg) |
| total_delivery_seconds | BIGINT | Sum of delivery durations |
| created_at | TIMESTAMPTZ | |

### analytics_cancellation_reasons

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| order_id | UUID | FK ? orders |
| pharmacy_id | UUID | FK ? pharmacies |
| zone_id | UUID | FK ? zones |
| cancel_stage | VARCHAR(15) | PRE_ACCEPT, POST_ACCEPT |
| cancel_reason | VARCHAR(50) | Reason code |
| cancel_actor | VARCHAR(10) | CUSTOMER, PHARMACY, SYSTEM |
| cancelled_at | TIMESTAMPTZ | |

## Acceptance Criteria

1. **AC-001**: GET /operations with period=TODAY returns `live_orders_now` reflecting current count of non-terminal orders.
2. **AC-002**: GET /fulfilment-funnel returns exactly 5 stages in fixed order; drop_off_pct for `orders_placed` stage is null.
3. **AC-003**: GET /delivery-breakdown returns P50 and P90 for pharmacy_prep, rider_pickup, delivery, and total for each zone.
4. **AC-004**: GET /cancellations groups reasons under `customer`, `pharmacy`, and `system` actors; percentages sum to 100%.
5. **AC-005**: `sla_adherence_pct` accurately reflects only delivered orders against SLA threshold; non-delivered orders are excluded.
6. **AC-006**: Zone filter on any ops endpoint returns data scoped to that zone only; invalid zone_id returns 404.
7. **AC-007**: `fill_rate_pct` excludes orders cancelled before pharmacy acceptance from the denominator.
8. **AC-008**: Admin Command Center widget using GET /operations?period=TODAY auto-refreshes every 30 seconds.

## Dependencies

| Dependency | Type | Notes |
|-----------|------|-------|
| EPIC-001 Order Management | Data source | Order lifecycle status and timestamps |
| EPIC-004 Dispatch & Rider Assignment | Data source | Rider pickup and delivery timestamps |
| EPIC-016-STORY-001 | Analytics foundation | Pre-aggregation infrastructure |
| Zone configuration | Config | SLA thresholds per zone |

## Notes

- P50/P90 computation on raw data uses `PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY duration)` SQL window function.
- `live_orders_now` for the Command Center bypasses the pre-aggregation layer and queries the orders table directly.
- Future: funnel may be extended with `PRESCRIPTION_REVIEW` stage for Rx orders.
