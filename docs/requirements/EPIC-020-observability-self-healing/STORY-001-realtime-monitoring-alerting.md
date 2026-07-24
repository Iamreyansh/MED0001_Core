# STORY-001: Real-Time Monitoring & Alerting

| Field | Value |
|-------|-------|
| Story ID | EPIC-020-STORY-001 |
| Epic | EPIC-020 Observability and Self-Healing |
| Title | Real-Time Monitoring & Alerting |
| Priority | P1 |
| Status | In Development |
| Role | admin_super, admin_operations |
| Last Updated | 2026-07-24 |

## Overview

The Real-Time Monitoring & Alerting story provides the live platform health dashboard for the Admin Command Center. It exposes GMV-per-hour, orders-per-minute, dispatch success rates, payment success rates, and zone coverage statuses. An alerts endpoint surfaces active anomaly alerts (GMV drop, dispatch failure, zone dark, payout spike, payment failure, SLA breach rate) with severity classification. Alert acknowledgement and metric time-series endpoints power the live charts. An SLO dashboard tracks error budget remaining for each defined SLO.

## User Roles

| Role | Access |
|------|--------|
| admin_super | Full access |
| admin_operations | Full access |
| admin_finance | GMV and payment metrics only |
| admin_support | Read alert list only |

## Business Rules

1. **Metric Collection Interval**: All metrics are collected every 60 seconds. The metrics time-series endpoint returns data at 60-second granularity.
2. **GMV Drop Alert**: Alert fires when actual GMV in the current hour is < 50% of the same-hour average from the same day of week in the previous 4 weeks. Severity: CRITICAL.
3. **Zone Dark Alert**: Fires when a zone has 0 online riders for > 30 consecutive minutes. Severity: HIGH. One alert per zone.
4. **Payout Spike Alert**: Fires when the total payout amount processed in the last 1 hour exceeds 3- the 7-day hourly average. Severity: HIGH.
5. **Payment Failure Alert**: Fires when payment success rate drops below 95% in a rolling 15-minute window (minimum 20 attempts). Severity: CRITICAL if < 90%, HIGH if 90-95%.
6. **SLA Breach Rate Alert**: Fires when SLA adherence drops below 80% in the last 1 hour. Severity: HIGH.
7. **Alert Auto-Resolution**: Alerts automatically resolve when the triggering metric recovers above the threshold for 2 consecutive collection intervals (120 seconds).
8. **Critical Alert Paging**: CRITICAL severity alerts trigger a push notification (HIGH priority) AND an SMS to all online admin_super and admin_operations users.
9. **Alert History Retention**: Alert records (both active and resolved) are retained for 90 days.
10. **SLO Error Budget**: `error_budget_remaining_pct = 100 - ((target_pct - current_pct) / (100 - target_pct) - 100)`. When error budget reaches 0%, a CRITICAL alert fires and non-essential deployments should be frozen.
11. **Metrics Store**: Time-series metrics are stored in a time-series database (TimescaleDB or InfluxDB). The metrics API queries this store directly.
12. **No Data Handling**: If a metric has no data for the requested period (system offline, no orders), the API returns `null` for that data point rather than 0, to avoid false anomaly detection.

## API Endpoints

### GET /api/v1/admin/monitoring/realtime

Live platform health overview for the Command Center.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Response 200**
```json
{
  "success": true,
  "data": {
    "gmv_last_hour_paise": 612000,
    "orders_per_minute": 2.8,
    "dispatch_success_rate_pct": 97.4,
    "sla_adherence_pct": 92.8,
    "payment_success_rate_pct": 98.9,
    "zone_coverage": {
      "healthy": 9,
      "stretched": 2,
      "dark": 1
    },
    "active_automations": 8,
    "pending_approvals": 3,
    "as_of": "2026-07-24T10:00:00Z",
    "data_age_seconds": 42
  },
  "meta": {}
}
```

**Error Table**

| HTTP Code | Error Code | Condition |
|-----------|-----------|-----------|
| 403 | FORBIDDEN | Insufficient role |
| 503 | METRICS_UNAVAILABLE | Metrics store unreachable |

---

### GET /api/v1/admin/monitoring/alerts

List active and recently resolved alerts.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`, `admin_support`

**Query Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| status | string | No | ACTIVE, ACKNOWLEDGED, RESOLVED (default: ACTIVE) |
| severity | string | No | CRITICAL, HIGH, MEDIUM, LOW |
| page | integer | No | Default 1 |

**Response 200**
```json
{
  "success": true,
  "data": {
    "alerts": [
      {
        "id": "uuid-alert-1",
        "severity": "CRITICAL",
        "type": "GMV_DROP",
        "message": "GMV in the last hour (Rs 6,120) is 42% below same-hour last week (Rs 10,534). Investigate immediately.",
        "triggered_at": "2026-07-24T09:00:00Z",
        "acknowledged": false,
        "acknowledged_by": null,
        "acknowledged_at": null,
        "auto_remediated": false,
        "resolved_at": null
      },
      {
        "id": "uuid-alert-2",
        "severity": "HIGH",
        "type": "ZONE_DARK",
        "message": "Zone 'Whitefield' has had 0 online riders for 38 minutes.",
        "triggered_at": "2026-07-24T09:22:00Z",
        "acknowledged": true,
        "acknowledged_by": "admin@nammamedmate.in",
        "acknowledged_at": "2026-07-24T09:35:00Z",
        "auto_remediated": true,
        "resolved_at": null
      }
    ]
  },
  "meta": { "page": 1, "limit": 20, "total": 3 }
}
```

---

### POST /api/v1/admin/monitoring/alerts/:id/acknowledge

Acknowledge an alert with optional notes.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Request Body**
```json
{
  "notes": "Investigating zone dark alert. Contacting riders via WhatsApp."
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "alert_id": "uuid-alert-2",
    "acknowledged": true,
    "acknowledged_by": "uuid-admin-1",
    "acknowledged_at": "2026-07-24T10:02:00Z"
  },
  "meta": {}
}
```

**Error Table**

| HTTP Code | Error Code | Condition |
|-----------|-----------|-----------|
| 404 | ALERT_NOT_FOUND | Alert ID not found |
| 409 | ALREADY_ACKNOWLEDGED | Alert already acknowledged |

---

### GET /api/v1/admin/monitoring/metrics

Retrieve time-series metric data for charting.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Query Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| metric_name | string | Yes | gmv, order_count, dispatch_rate, sla_pct, payment_success_pct, rider_online_count |
| period_minutes | integer | No | Rolling window: 60, 180, 360, 1440 (default 60) |

**Response 200**
```json
{
  "success": true,
  "data": {
    "metric_name": "sla_pct",
    "period_minutes": 180,
    "data_points": [
      { "timestamp": "2026-07-24T07:00:00Z", "value": 94.2 },
      { "timestamp": "2026-07-24T07:01:00Z", "value": 93.8 },
      { "timestamp": "2026-07-24T07:02:00Z", "value": 92.4 },
      { "timestamp": "2026-07-24T09:59:00Z", "value": 92.8 }
    ],
    "current_value": 92.8,
    "slo_target": 95.0
  },
  "meta": {}
}
```

**Error Table**

| HTTP Code | Error Code | Condition |
|-----------|-----------|-----------|
| 400 | INVALID_METRIC | metric_name not in supported set |
| 400 | INVALID_PERIOD | period_minutes not in allowed set |
| 403 | FORBIDDEN | Role cannot access this metric |

---

### GET /api/v1/admin/monitoring/slo

Retrieve SLO dashboard with error budget tracking.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Response 200**
```json
{
  "success": true,
  "data": {
    "slos": [
      {
        "slo_name": "order_sla_adherence",
        "description": "95% of orders delivered within 45 minutes",
        "target_pct": 95.0,
        "current_pct_30d": 93.2,
        "error_budget_remaining_pct": 74.0,
        "last_30d_pct": 93.2,
        "trend": "DEGRADING",
        "compliant": false
      },
      {
        "slo_name": "payment_success",
        "description": "99% of payment captures succeed",
        "target_pct": 99.0,
        "current_pct_30d": 99.4,
        "error_budget_remaining_pct": 140.0,
        "last_30d_pct": 99.4,
        "trend": "STABLE",
        "compliant": true
      },
      {
        "slo_name": "dispatch_success",
        "description": "98% of orders assigned within 10 minutes",
        "target_pct": 98.0,
        "current_pct_30d": 97.8,
        "error_budget_remaining_pct": 90.0,
        "last_30d_pct": 97.8,
        "trend": "STABLE",
        "compliant": false
      },
      {
        "slo_name": "api_p99_latency",
        "description": "API P99 latency < 500ms",
        "target_pct": 100.0,
        "current_pct_30d": 99.2,
        "error_budget_remaining_pct": 20.0,
        "last_30d_pct": 99.2,
        "trend": "STABLE",
        "compliant": false
      }
    ]
  },
  "meta": {}
}
```

---

## Data Models

### monitoring_alerts

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| severity | VARCHAR(10) | CRITICAL, HIGH, MEDIUM, LOW |
| type | VARCHAR(30) | GMV_DROP, DISPATCH_FAILURE, ZONE_DARK, PAYOUT_SPIKE, PAYMENT_FAILURE, SLA_BREACH_RATE |
| message | TEXT | Human-readable alert message |
| triggering_metric | VARCHAR(60) | Metric that caused the alert |
| triggering_value | DECIMAL(10,4) | Actual metric value at alert time |
| threshold_value | DECIMAL(10,4) | Threshold that was breached |
| zone_id | UUID | Nullable; for zone-specific alerts |
| triggered_at | TIMESTAMPTZ | |
| acknowledged | BOOLEAN | Default false |
| acknowledged_by | UUID | FK ? admin_users |
| acknowledged_at | TIMESTAMPTZ | |
| acknowledged_notes | TEXT | |
| auto_remediated | BOOLEAN | Default false |
| resolved_at | TIMESTAMPTZ | Nullable |
| resolution_reason | VARCHAR(50) | AUTO_RESOLVED, MANUAL_RESOLVED |

### slo_definitions

| Column | Type | Notes |
|--------|------|-------|
| slo_name | VARCHAR(60) | PK |
| description | TEXT | |
| target_pct | DECIMAL(5,2) | |
| metric_name | VARCHAR(60) | Source metric |
| measurement_window_days | INTEGER | Default 30 |

### slo_compliance_history

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| slo_name | VARCHAR(60) | FK ? slo_definitions |
| period_from | DATE | |
| period_to | DATE | |
| target_pct | DECIMAL(5,2) | |
| actual_pct | DECIMAL(5,2) | |
| compliant | BOOLEAN | |
| error_budget_consumed_pct | DECIMAL(8,2) | |
| recorded_at | TIMESTAMPTZ | |

## Acceptance Criteria

1. **AC-001**: GET /realtime returns `data_age_seconds` < 120 (data is never staler than 2 minutes).
2. **AC-002**: A GMV_DROP alert fires when actual GMV < 50% of same-hour previous week average; severity is CRITICAL.
3. **AC-003**: CRITICAL alert fires a HIGH-priority push notification AND SMS to all active admin_super and admin_operations users.
4. **AC-004**: Alerts auto-resolve when the triggering metric recovers above threshold for 2 consecutive collection intervals; `resolved_at` is set and `status` becomes RESOLVED.
5. **AC-005**: GET /metrics returns null data points for minutes with no data (not 0), to avoid false anomaly detection.
6. **AC-006**: GET /slo shows `error_budget_remaining_pct: 0` or negative when SLO target is not met; this triggers a CRITICAL alert.
7. **AC-007**: POST /alerts/:id/acknowledge for an already-acknowledged alert returns `409 ALREADY_ACKNOWLEDGED`.
8. **AC-008**: GET /metrics for `sla_pct` with `period_minutes=1440` returns 1,440 data points (one per minute for 24 hours).

## Dependencies

| Dependency | Type | Notes |
|-----------|------|-------|
| Metrics time-series store | Infrastructure | TimescaleDB or InfluxDB |
| EPIC-001 Order Management | Data source | Order SLA, delivery times |
| EPIC-004 Dispatch | Data source | Dispatch success, rider online |
| EPIC-005 Finance | Data source | Payment success rates, payout volume |
| EPIC-017 Notifications | Alert transport | Push + SMS for critical alerts |
| EPIC-020-STORY-002 | Auto-remediation | Triggers remediation on certain alerts |

## Notes

- The `data_age_seconds` field in the realtime endpoint tells the frontend how fresh the data is. If data_age > 120 seconds, display a "data may be delayed" warning.
- Alert deduplication: if the same alert type + zone_id is already ACTIVE, a new trigger does not create a duplicate alert. It updates the existing alert's `triggered_at`.
- Zone `stretched` = `riders_online` < demand threshold but > 0. Zone `dark` = `riders_online = 0` for > 30 min.
