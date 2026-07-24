# STORY-004-002: Pharmacy Performance Metrics

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-004-002 |
| **Epic** | EPIC-004 - Pharmacy Operations (Admin View) |
| **Priority** | P0 |
| **Complexity** | M |
| **Status** | Draft |

---

## Overview

This story covers the performance monitoring system for pharmacies - tracking key operational KPIs (fill rate, on-time preparation, cancellation rate, out-of-stock rate, average prep time, complaint count, and customer ratings). Admin operations staff use these metrics to identify underperforming pharmacies, send corrective alerts, and trigger escalation workflows. Performance data is computed daily via a batch job and cached in Redis; real-time accuracy is intentional sacrificed for query performance. Automated warning and suspension rules are implemented in the rules engine (EPIC-011) but are configured and monitored through this story's endpoints. Customer ratings are surfaced per-pharmacy with individual review records.

---

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| `admin_super` | Full | View metrics, ratings, orders; send performance alerts; configure thresholds |
| `admin_operations` | Full | View metrics, ratings, orders; send performance alerts |
| `admin_support` | Read | View performance metrics and ratings only |
| `admin_compliance` | Read | View fill rate and out-of-stock metrics |
| `pharmacy_owner` | Read (own) | Can view their own performance dashboard (via pharmacy dashboard API, not admin API) |

---

## Business Rules

1. **Fill rate calculation**: `fill_rate_pct = (orders_fulfilled / orders_received) - 100` where `orders_fulfilled` counts orders that reached `DELIVERED` or `PICKED_UP` status and `orders_received` counts all orders assigned to the pharmacy (excluding orders cancelled by the customer before pharmacy acceptance).
2. **On-time preparation calculation**: `on_time_prep_pct = (orders_prepped_within_sla / total_accepted_orders) - 100`. The SLA prep window is 20 minutes for standard orders and 10 minutes for express orders. Exceeded SLA orders are counted regardless of whether delivery was on time.
3. **Auto-warning threshold**: The rules engine (EPIC-011) triggers an automatic performance warning notification to the pharmacy when `fill_rate_pct < 85%` for the trailing 7 days OR `cancel_rate_pct > 15%` for the trailing 7 days. Admin is also notified of the auto-warning via in-app.
4. **Auto-suspension trigger**: The rules engine triggers automatic suspension when `fill_rate_pct < 70%` for 3 consecutive calendar days. Before executing suspension, admin is notified with a 1-hour window to override.
5. **Performance data is computed daily and cached**: A batch job runs at 02:00 IST every day and computes trailing-7-day and trailing-30-day metrics for all active pharmacies. Results are stored in a `PharmacyPerformanceSnapshot` table and cached in Redis with a 4-hour TTL. The `period` query parameter determines which snapshot set is returned.
6. **Alert throttling**: Admin can send at most 1 performance alert of the same `alert_type` per pharmacy per 24 hours. Attempts beyond this limit return `ALERT_THROTTLED` with the time until the next alert is allowed.
7. **Rating calculation**: `avg_rating` is the arithmetic mean of all `OrderRating.rating` values for that pharmacy. Only ratings from `DELIVERED` or `PICKED_UP` orders older than 1 hour (to prevent immediate-flip manipulation) are included. Ratings are re-computed as part of the nightly batch job.
8. **Out-of-stock rate**: `out_of_stock_rate_pct = (order_items_marked_oos / total_order_items_received) - 100`. Items marked OOS by the pharmacy during order processing are the numerator.

---

## API Endpoints

### 1. Get Pharmacy Performance Metrics

```
GET /api/v1/admin/pharmacies/:id/performance
```

**Authentication:** Bearer JWT - `admin_super`, `admin_operations`, `admin_support`, `admin_compliance`
**Rate Limit:** 60 req/min

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| `id` | UUID | Pharmacy ID |

**Query Parameters:**
| Param | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `period` | string | No | `30d` | Metric period: `7d` \| `30d` \| `90d` |

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "pharmacy_id": "uuid-v4",
    "business_name": "Sharma Medical Store",
    "period": "30d",
    "period_start": "2026-06-24",
    "period_end": "2026-07-24",
    "metrics": {
      "fill_rate_pct": 91.2,
      "on_time_prep_pct": 88.5,
      "cancel_rate_pct": 3.1,
      "out_of_stock_rate_pct": 6.3,
      "avg_prep_minutes": 14.2,
      "complaint_count": 4,
      "avg_rating": 4.3,
      "review_count": 128,
      "orders_received": 842,
      "orders_fulfilled": 768,
      "orders_cancelled": 26,
      "gmv_period": 485000.00
    },
    "alerts": {
      "auto_warning_triggered": false,
      "auto_suspension_risk": false,
      "consecutive_low_fill_rate_days": 0
    },
    "thresholds": {
      "fill_rate_warning_pct": 85,
      "fill_rate_suspension_pct": 70,
      "cancel_rate_warning_pct": 15,
      "on_time_prep_warning_pct": 80
    },
    "trend": {
      "fill_rate_trend": "IMPROVING",
      "cancel_rate_trend": "STABLE"
    },
    "computed_at": "2026-07-24T02:00:00Z"
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `INVALID_PERIOD` | `period` not in allowed values |
| 403 | `FORBIDDEN` | Caller not an admin role |
| 404 | `PHARMACY_NOT_FOUND` | Pharmacy ID not found |

---

### 2. Get Pharmacy Ratings List

```
GET /api/v1/admin/pharmacies/:id/ratings
```

**Authentication:** Bearer JWT - `admin_super`, `admin_operations`, `admin_support`
**Rate Limit:** 60 req/min

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| `id` | UUID | Pharmacy ID |

**Query Parameters:**
| Param | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `rating` | integer | No | - | Filter by rating value (1-5) |
| `sort` | string | No | `created_at` | created_at \| rating |
| `order` | string | No | `desc` | asc \| desc |
| `page` | integer | No | 1 | Page number |
| `limit` | integer | No | 20 | Records per page, max 100 |

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "pharmacy_id": "uuid-v4",
    "avg_rating": 4.3,
    "review_count": 128,
    "rating_distribution": {
      "5": 68,
      "4": 38,
      "3": 14,
      "2": 5,
      "1": 3
    },
    "ratings": [
      {
        "rating_id": "uuid-v4",
        "order_id": "uuid-v4",
        "order_number": "ORD-20260724-0042",
        "customer_name": "Priya K.",
        "rating": 5,
        "review_text": "Very fast delivery, medicines were packed well.",
        "created_at": "2026-07-23T18:00:00Z"
      },
      {
        "rating_id": "uuid-v4",
        "order_id": "uuid-v4",
        "order_number": "ORD-20260722-0018",
        "customer_name": "Arun M.",
        "rating": 2,
        "review_text": "Two medicines were out of stock but not communicated upfront.",
        "created_at": "2026-07-22T12:00:00Z"
      }
    ]
  },
  "meta": {
    "page": 1,
    "limit": 20,
    "total": 128,
    "total_pages": 7
  }
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `INVALID_RATING_FILTER` | Rating filter not between 1 and 5 |
| 404 | `PHARMACY_NOT_FOUND` | Pharmacy ID not found |

---

### 3. Get Pharmacy Recent Orders (Admin)

```
GET /api/v1/admin/pharmacies/:id/orders
```

**Authentication:** Bearer JWT - `admin_super`, `admin_operations`, `admin_support`
**Rate Limit:** 60 req/min

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| `id` | UUID | Pharmacy ID |

**Query Parameters:**
| Param | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `status` | string | No | ALL | Order status filter |
| `from_date` | date | No | 30 days ago | Start of date range (YYYY-MM-DD) |
| `to_date` | date | No | today | End of date range (YYYY-MM-DD) |
| `page` | integer | No | 1 | Page number |
| `limit` | integer | No | 20 | Records per page, max 100 |

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "pharmacy_id": "uuid-v4",
    "orders": [
      {
        "order_id": "uuid-v4",
        "order_number": "ORD-20260724-0042",
        "status": "DELIVERED",
        "customer_name": "Priya K.",
        "item_count": 3,
        "total_amount": 450.00,
        "prep_minutes": 12,
        "prep_on_time": true,
        "has_rx": false,
        "created_at": "2026-07-24T08:00:00Z",
        "delivered_at": "2026-07-24T08:35:00Z"
      }
    ]
  },
  "meta": {
    "page": 1,
    "limit": 20,
    "total": 34,
    "total_pages": 2
  }
}
```

---

### 4. Send Performance Alert to Pharmacy

```
POST /api/v1/admin/pharmacies/:id/performance/alert
```

**Authentication:** Bearer JWT - `admin_super`, `admin_operations`
**Rate Limit:** 10 req/min per admin

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| `id` | UUID | Pharmacy ID |

**Request Body (application/json):**
```json
{
  "alert_type": "string - required, enum: LOW_FILL_RATE | HIGH_CANCEL_RATE | OFFLINE_PEAK_HOURS | LOW_RATING | HIGH_OOS_RATE | SLOW_PREP_TIME",
  "threshold_value": "number - required, the metric value that triggered the alert (for context in the message)",
  "message": "string - optional, custom message to include; max 500 chars; default template used if omitted"
}
```

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "pharmacy_id": "uuid-v4",
    "alert_type": "LOW_FILL_RATE",
    "threshold_value": 78.5,
    "channels_notified": ["WHATSAPP", "IN_APP"],
    "sent_at": "2026-07-24T00:00:00Z",
    "next_alert_allowed_at": "2026-07-25T00:00:00Z"
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `INVALID_ALERT_TYPE` | `alert_type` not in allowed enum |
| 400 | `THRESHOLD_VALUE_REQUIRED` | `threshold_value` is missing |
| 403 | `FORBIDDEN` | Caller not admin_super or admin_operations |
| 404 | `PHARMACY_NOT_FOUND` | Pharmacy ID not found |
| 429 | `ALERT_THROTTLED` | Same alert type already sent to this pharmacy within 24 hours |

---

## Data Models

### PharmacyPerformanceSnapshot

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID v4 | PK | Snapshot record ID |
| `pharmacy_id` | UUID | FK ? Pharmacy.id, not null, indexed | Owning pharmacy |
| `period` | ENUM | Not null | 7D \| 30D \| 90D |
| `period_start` | DATE | Not null | Start of metric period |
| `period_end` | DATE | Not null | End of metric period |
| `orders_received` | INTEGER | Not null | Total orders assigned to pharmacy |
| `orders_fulfilled` | INTEGER | Not null | Orders delivered/picked up |
| `orders_cancelled` | INTEGER | Not null | Orders cancelled by pharmacy |
| `fill_rate_pct` | DECIMAL(5,2) | Not null | Fill rate percentage |
| `on_time_prep_pct` | DECIMAL(5,2) | Not null | On-time prep percentage |
| `cancel_rate_pct` | DECIMAL(5,2) | Not null | Cancellation rate |
| `out_of_stock_rate_pct` | DECIMAL(5,2) | Not null | Out-of-stock rate |
| `avg_prep_minutes` | DECIMAL(5,1) | Not null | Average order preparation time |
| `complaint_count` | INTEGER | Not null, default 0 | Support complaints logged |
| `avg_rating` | DECIMAL(3,2) | Not null | Average customer rating |
| `review_count` | INTEGER | Not null | Total reviews in period |
| `gmv_period` | DECIMAL(14,2) | Not null | Gross merchandise value |
| `consecutive_low_fill_days` | SMALLINT | Not null, default 0 | Days below suspension threshold |
| `computed_at` | TIMESTAMPTZ | Not null | When batch job computed this snapshot |

### PerformanceAlert

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID v4 | PK | Alert record ID |
| `pharmacy_id` | UUID | FK ? Pharmacy.id, not null | Target pharmacy |
| `alert_type` | ENUM | Not null | LOW_FILL_RATE \| HIGH_CANCEL_RATE \| OFFLINE_PEAK_HOURS \| LOW_RATING \| HIGH_OOS_RATE \| SLOW_PREP_TIME |
| `triggered_by` | UUID | FK ? User.id, nullable | Admin; null if auto-triggered by rules engine |
| `threshold_value` | DECIMAL(10,2) | Not null | Metric value that triggered alert |
| `message` | TEXT | Nullable | Custom or template-generated message |
| `channels` | TEXT[] | Not null | Channels used: WHATSAPP, EMAIL, IN_APP |
| `sent_at` | TIMESTAMPTZ | Not null | Alert dispatch timestamp |

---

## Acceptance Criteria

- [ ] **Given** GET `/api/v1/admin/pharmacies/:id/performance?period=30d`, **then** the response includes `fill_rate_pct`, `on_time_prep_pct`, `cancel_rate_pct`, `out_of_stock_rate_pct`, `avg_prep_minutes`, `avg_rating`, and `review_count` computed for the trailing 30 days, with `computed_at` indicating the batch job run time.
- [ ] **Given** a pharmacy with `fill_rate_pct=78.5` (below the 85% warning threshold), **then** `alerts.auto_warning_triggered=true` is reflected in the performance response.
- [ ] **Given** `fill_rate_pct < 70%` for 3 consecutive calendar days, **then** `alerts.auto_suspension_risk=true` and `alerts.consecutive_low_fill_rate_days=3` are returned.
- [ ] **Given** POST `/api/v1/admin/pharmacies/:id/performance/alert` with `alert_type=LOW_FILL_RATE` is called, **then** the pharmacy receives a WhatsApp and in-app notification, a `PerformanceAlert` record is stored, and `next_alert_allowed_at` is set to 24 hours from now.
- [ ] **Given** the same `alert_type` alert is sent again within 24 hours, **then** HTTP 429 `ALERT_THROTTLED` is returned with `next_alert_allowed_at`.
- [ ] **Given** GET `/api/v1/admin/pharmacies/:id/ratings?rating=1`, **then** only 1-star ratings are returned for that pharmacy with customer name, review text, order number, and timestamp.
- [ ] **Given** GET `/api/v1/admin/pharmacies/:id/orders?from_date=2026-07-01&to_date=2026-07-24`, **then** only orders within that date range for that pharmacy are returned with `prep_on_time` flag per order.
- [ ] **Given** performance data is not yet computed (new pharmacy with no orders), **then** all metric fields return `0` or `null` with `computed_at=null` and no errors.

---

## Dependencies

- STORY-004-001 - Pharmacy Directory (performance data shown in detail drawer)
- EPIC-008 - Orders (order data, delivery timestamps, cancellation events)
- EPIC-011 - Rules Engine (auto-warning and auto-suspension triggers)
- EPIC-002 - Notifications (performance alert delivery via WhatsApp, in-app)
- Infrastructure: Redis - performance metric cache; nightly batch job (cron)

---

## Notes

- The nightly batch job (`pharmacy_performance_aggregator`) runs at 02:00 IST using a cron schedule. It iterates all ACTIVE pharmacies and computes 7D, 30D, and 90D snapshots in a single transaction per pharmacy.
- `avg_prep_minutes` is calculated as the mean of `(order_marked_ready_at - order_accepted_at)` in minutes for all accepted orders in the period. Orders not yet marked ready are excluded.
- `trend` fields (`fill_rate_trend`, `cancel_rate_trend`) are computed by comparing the current 7-day window to the previous 7-day window: IMPROVING = current > previous by ? 2pp; DECLINING = current < previous by ? 2pp; STABLE = within -2pp.
- Customer names in the ratings list are partially masked: first name shown in full, last name as initial only (e.g., "Priya K.") for privacy.
- Performance alert WhatsApp template names: `PHARMACY_ALERT_LOW_FILL_RATE`, `PHARMACY_ALERT_HIGH_CANCEL_RATE`, etc. All must be pre-approved by Meta.
