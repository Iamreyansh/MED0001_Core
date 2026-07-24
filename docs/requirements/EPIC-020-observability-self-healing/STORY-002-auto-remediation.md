# STORY-002: Auto-Remediation

| Field | Value |
|-------|-------|
| Story ID | EPIC-020-STORY-002 |
| Epic | EPIC-020 Observability and Self-Healing |
| Title | Auto-Remediation |
| Priority | P1 |
| Status | In Development |
| Role | admin_super, admin_operations |
| Last Updated | 2026-07-24 |

## Overview

The Auto-Remediation story implements automated self-healing playbooks that trigger in response to monitoring alerts. Predefined remediation playbooks map alert types to specific corrective actions (requesting riders to come online, throttling low-performing pharmacies, retrying failed payment jobs, clearing caches, pausing underperforming promotions). Remediation actions are logged separately from the automation rules engine activity log. Admins can view remediation history, configure playbooks, and trigger manual remediation for immediate intervention.

## User Roles

| Role | Access |
|------|--------|
| admin_super | Full access; trigger manual remediation; update playbooks |
| admin_operations | Full access; trigger manual remediation |

## Business Rules

1. **Playbook vs Rules Engine**: Auto-remediation playbooks are distinct from EPIC-019 automation rules. They are predefined system-level responses to monitoring anomalies and are not configurable via the rules builder. They are always on (when enabled) and do not go through the approvals queue.
2. **Zone Dark Playbook**: When `ZONE_DARK` alert fires for a zone, the platform sends push notifications to all riders in that zone who are offline, requesting them to come online. Maximum 3 notifications per rider per 2-hour window.
3. **Fill Rate Throttling**: When a pharmacy's fill_rate < 70% for 3 consecutive days, the platform reduces their max concurrent order cap by 30%. This is reversed automatically when fill_rate recovers above 80% for 2 consecutive days.
4. **Payment Job Retry**: When a payment processing job fails (alert type: PAYMENT_JOB_FAILURE), the system automatically retries after 5 minutes. Maximum 3 auto-retries. After 3 failures, a CRITICAL alert fires and human intervention is required.
5. **API Error Rate Paging**: When error_rate on any single API endpoint exceeds 5% in a 5-minute window, the system pages the on-call engineer via push notification and SMS. This is informational (not a corrective action).
6. **Remediation Audit Log**: Every remediation action is logged in `monitoring_remediation_log` with: alert_id, action_type, target_entity_id, result, triggered_at. This is separate from the automation activity log.
7. **Playbook Enable/Disable**: Each playbook has an `is_enabled` toggle. Disabled playbooks do not fire. Toggling is audit-logged.
8. **Threshold Configuration**: Each playbook has configurable thresholds (e.g., zone_dark duration before trigger, fill_rate percentage, error_rate percentage). Defaults are conservative.
9. **Reversibility**: `REQUEST_RIDERS` action is informational (push notification) - inherently reversible (just notifications). `THROTTLE_PHARMACY` is reversible via PATCH playbook or auto-recovery condition. `RETRY_PAYMENT_JOB` is idempotent.
10. **Manual Trigger**: Admins can manually trigger a remediation action for a specific entity without waiting for the alert condition. Manual triggers bypass playbook thresholds.

## API Endpoints

### GET /api/v1/admin/monitoring/remediation-actions

List recently triggered remediation actions.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Query Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| action_type | string | No | REQUEST_RIDERS, THROTTLE_PHARMACY, RETRY_PAYMENT_JOB, CLEAR_CACHE, PAUSE_PROMOTION |
| status | string | No | INITIATED, SUCCESS, FAILED |
| date_from | string | No | ISO 8601 |
| date_to | string | No | ISO 8601 |
| page | integer | No | Default 1 |

**Response 200**
```json
{
  "success": true,
  "data": {
    "remediation_actions": [
      {
        "id": "uuid-rem-1",
        "alert_id": "uuid-alert-2",
        "action_type": "REQUEST_RIDERS",
        "trigger_type": "AUTO",
        "target_entity_type": "ZONE",
        "target_entity_id": "uuid-zone-4",
        "target_entity_name": "Whitefield",
        "action_details": {
          "riders_notified": 8,
          "notifications_sent": 8
        },
        "status": "SUCCESS",
        "triggered_at": "2026-07-24T09:52:00Z",
        "completed_at": "2026-07-24T09:52:04Z",
        "triggered_by": "SYSTEM"
      },
      {
        "id": "uuid-rem-2",
        "alert_id": null,
        "action_type": "THROTTLE_PHARMACY",
        "trigger_type": "MANUAL",
        "target_entity_type": "PHARMACY",
        "target_entity_id": "uuid-ph-5",
        "target_entity_name": "Medplus - HSR Layout",
        "action_details": {
          "previous_order_cap": 20,
          "new_order_cap": 14,
          "throttle_reason": "fill_rate below 70% for 3 days"
        },
        "status": "SUCCESS",
        "triggered_at": "2026-07-23T18:00:00Z",
        "completed_at": "2026-07-23T18:00:02Z",
        "triggered_by": "uuid-admin-1"
      }
    ]
  },
  "meta": { "page": 1, "limit": 20, "total": 24 }
}
```

---

### POST /api/v1/admin/monitoring/remediation-actions

Manually trigger a remediation action.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Request Body**
```json
{
  "action_type": "REQUEST_RIDERS",
  "target_entity_type": "ZONE",
  "target_entity_id": "uuid-zone-4",
  "reason": "Zone Whitefield going dark during peak hours. Manually requesting riders to come online."
}
```

**Response 202**
```json
{
  "success": true,
  "data": {
    "remediation_id": "uuid-rem-3",
    "action_type": "REQUEST_RIDERS",
    "target_entity_id": "uuid-zone-4",
    "status": "INITIATED",
    "triggered_at": "2026-07-24T10:05:00Z"
  },
  "meta": {}
}
```

**Error Table**

| HTTP Code | Error Code | Condition |
|-----------|-----------|-----------|
| 400 | INVALID_ACTION_TYPE | action_type not in allowed set |
| 400 | ENTITY_NOT_FOUND | target_entity_id not found |
| 429 | RATE_LIMITED | Same action + entity triggered too recently |

---

### GET /api/v1/admin/monitoring/remediation-playbooks

List all remediation playbooks with their configuration.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Response 200**
```json
{
  "success": true,
  "data": {
    "playbooks": [
      {
        "id": "uuid-pb-1",
        "alert_type": "ZONE_DARK",
        "auto_remediation_action": "REQUEST_RIDERS",
        "description": "Send push notifications to offline riders in the dark zone to come online.",
        "threshold": {
          "dark_duration_minutes": 30,
          "max_notifications_per_rider": 3,
          "notification_cooldown_hours": 2
        },
        "is_enabled": true,
        "last_triggered_at": "2026-07-24T09:52:00Z"
      },
      {
        "id": "uuid-pb-2",
        "alert_type": "LOW_FILL_RATE",
        "auto_remediation_action": "THROTTLE_PHARMACY",
        "description": "Reduce pharmacy max concurrent order cap by 30% when fill_rate < threshold for N consecutive days.",
        "threshold": {
          "fill_rate_pct": 70,
          "consecutive_days": 3,
          "throttle_pct": 30,
          "recovery_fill_rate_pct": 80,
          "recovery_consecutive_days": 2
        },
        "is_enabled": true,
        "last_triggered_at": "2026-07-23T18:00:00Z"
      },
      {
        "id": "uuid-pb-3",
        "alert_type": "PAYMENT_JOB_FAILURE",
        "auto_remediation_action": "RETRY_PAYMENT_JOB",
        "description": "Retry failed payment processing job after delay.",
        "threshold": {
          "retry_delay_minutes": 5,
          "max_retries": 3
        },
        "is_enabled": true,
        "last_triggered_at": null
      },
      {
        "id": "uuid-pb-4",
        "alert_type": "API_ERROR_RATE_HIGH",
        "auto_remediation_action": "PAGE_ON_CALL",
        "description": "Page the on-call engineer when API error rate > 5% on any endpoint.",
        "threshold": {
          "error_rate_pct": 5,
          "window_minutes": 5
        },
        "is_enabled": true,
        "last_triggered_at": null
      }
    ]
  },
  "meta": {}
}
```

---

### PATCH /api/v1/admin/monitoring/remediation-playbooks/:id

Update playbook configuration or toggle enabled state.

**Auth**: Bearer JWT - `admin_super`

**Path Parameters**

| Parameter | Type | Description |
|-----------|------|-------------|
| id | UUID | Playbook ID |

**Request Body**
```json
{
  "is_enabled": false,
  "threshold": {
    "dark_duration_minutes": 45
  }
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "playbook_id": "uuid-pb-1",
    "is_enabled": false,
    "threshold": {
      "dark_duration_minutes": 45,
      "max_notifications_per_rider": 3,
      "notification_cooldown_hours": 2
    },
    "updated_by": "uuid-admin-1",
    "updated_at": "2026-07-24T10:08:00Z"
  },
  "meta": {}
}
```

**Error Table**

| HTTP Code | Error Code | Condition |
|-----------|-----------|-----------|
| 403 | FORBIDDEN | Non-admin_super role |
| 404 | PLAYBOOK_NOT_FOUND | id not found |
| 422 | INVALID_THRESHOLD | Threshold value out of allowed range |

---

## Data Models

### monitoring_remediation_log

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| alert_id | UUID | FK ? monitoring_alerts (nullable for manual) |
| playbook_id | UUID | FK ? remediation_playbooks (nullable for manual) |
| action_type | VARCHAR(40) | REQUEST_RIDERS, THROTTLE_PHARMACY, RETRY_PAYMENT_JOB, CLEAR_CACHE, PAGE_ON_CALL, PAUSE_PROMOTION |
| trigger_type | VARCHAR(10) | AUTO, MANUAL |
| target_entity_type | VARCHAR(20) | ZONE, PHARMACY, PAYMENT_JOB |
| target_entity_id | UUID | |
| action_details | JSONB | Action-specific result data |
| status | VARCHAR(10) | INITIATED, SUCCESS, FAILED |
| triggered_by | UUID | FK ? admin_users (null if SYSTEM) |
| triggered_at | TIMESTAMPTZ | |
| completed_at | TIMESTAMPTZ | |
| error_message | TEXT | Nullable |

### remediation_playbooks

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| alert_type | VARCHAR(40) | FK ? alert types |
| auto_remediation_action | VARCHAR(40) | |
| description | TEXT | |
| threshold | JSONB | Configurable thresholds |
| is_enabled | BOOLEAN | Default true |
| last_triggered_at | TIMESTAMPTZ | Nullable |
| updated_by | UUID | FK ? admin_users |
| updated_at | TIMESTAMPTZ | |

## Acceptance Criteria

1. **AC-001**: ZONE_DARK alert firing (zone dark > 30 min) automatically triggers REQUEST_RIDERS remediation when the playbook is enabled; `trigger_type: AUTO` in the log.
2. **AC-002**: THROTTLE_PHARMACY reduces pharmacy's `max_concurrent_orders` by 30% of the previous value (rounded down) and logs `previous_order_cap` and `new_order_cap`.
3. **AC-003**: Payment job retry fires after 5 minutes; after 3 failed retries a new CRITICAL alert is created and no further auto-retries occur.
4. **AC-004**: PATCH /playbooks/:id with `is_enabled: false` stops the playbook from firing on new alerts; existing in-flight remediations complete.
5. **AC-005**: POST /remediation-actions (manual) for REQUEST_RIDERS on a zone sends push notifications to all offline riders in the zone.
6. **AC-006**: POST /remediation-actions (manual) with the same action_type + target_entity_id triggered within 5 minutes returns `429 RATE_LIMITED`.
7. **AC-007**: GET /remediation-actions shows both AUTO and MANUAL triggered actions with their `trigger_type` field.
8. **AC-008**: Playbook threshold changes are audit-logged with `updated_by` and a diff of changed threshold values.

## Dependencies

| Dependency | Type | Notes |
|-----------|------|-------|
| EPIC-020-STORY-001 | Trigger source | Monitoring alerts trigger playbooks |
| EPIC-017-STORY-001 | Transport | Push notifications to riders/admins |
| EPIC-017-STORY-002 | Transport | SMS to on-call engineer |
| EPIC-006 Pharmacy | Target | order cap throttling |
| EPIC-005 Finance | Target | Payment job retry |
| EPIC-007 Rider | Target | Rider push notifications |

## Notes

- The `PAGE_ON_CALL` action does not have a configurable target entity - it always pages the on-call rotation. The on-call schedule is maintained externally (PagerDuty/Opsgenie integration is a future enhancement). For now, paging is implemented as a push notification to all active admin_super users.
- THROTTLE_PHARMACY is a particularly impactful action - it affects customer order availability. The recovery condition (fill_rate > 80% for 2 days) ensures the throttle is automatically lifted as the pharmacy improves.
