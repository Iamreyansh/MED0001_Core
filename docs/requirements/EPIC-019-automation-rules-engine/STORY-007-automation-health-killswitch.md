# STORY-007: Automation Health & Kill Switch

| Field | Value |
|-------|-------|
| Story ID | EPIC-019-STORY-007 |
| Epic | EPIC-019 Automation and Rules Engine |
| Title | Automation Health & Kill Switch |
| Priority | P1 - Critical |
| Status | In Development |
| Role | admin_super, admin_operations |
| Last Updated | 2026-07-24 |

## Overview

The Automation Health & Kill Switch story provides real-time visibility into the automation engine's operational health and the most critical safety feature in the system: a global kill switch that pauses all rule evaluation and action execution instantly. The health dashboard surfaces per-rule fire rates, success rates, errors, and circuit breaker statuses. Circuit breakers prevent runaway automation by blocking an action type after it fires too frequently within an hour. The health dashboard refreshes every 60 seconds.

## User Roles

| Role | Access |
|------|--------|
| admin_super | Full access; can use kill switch |
| admin_operations | Read health dashboard; cannot use kill switch |

## Business Rules

1. **Kill Switch Scope**: POST /kill-switch with `action: PAUSE` halts all rule evaluation and action execution across the entire platform within 1 minute. All events currently being evaluated are dropped (not requeued). The kill switch is the single most critical safety feature of the engine.
2. **Pending Approvals Unaffected**: The kill switch does not cancel pending approvals. Human-driven approvals can still be approved or rejected while automation is paused. The approved actions execute immediately when automation resumes.
3. **Kill Switch Audit**: Every PAUSE and RESUME of the kill switch creates an immutable log entry with actor, timestamp, and reason. This log is separate from the automation_activity_log.
4. **Kill Switch Permissions**: Only `admin_super` can use the kill switch. admin_operations has read-only health dashboard access.
5. **Circuit Breaker Logic**: Each action type has a circuit breaker. If an action type fires more than `circuit_threshold` times in the last 60 minutes (configurable per action type, default: 50), the circuit opens. When open, that specific action type is blocked for all rules. The circuit auto-resets after 30 minutes.
6. **Circuit Breaker Scope**: Circuit breakers are per action_type (not per rule). If `apply_wallet_credit` trips its circuit, all rules attempting wallet credits are blocked until the circuit resets.
7. **Per-Rule Health**: The `/health/per-rule` endpoint provides `fire_count_24h`, `success_rate` (EXECUTED / (EXECUTED + EXCEPTION)), `last_error`, and `avg_execution_ms` for every rule in the last 24 hours.
8. **Dashboard Refresh**: The `/health` endpoint data is cached for 60 seconds and refreshed on each expiry. Clients should poll at ? 60-second intervals.
9. **Manual Actions Saved Estimate**: This metric is computed as total AUTOMATION-actor activity log entries in the last 30 days, representing decisions the automation made that would otherwise require human action.
10. **Kill Switch Status on Resume**: On RESUME, the engine begins consuming events from the queue again. Events that arrived during PAUSE were acknowledged (not requeued), so they are lost. This is by design - the kill switch is for emergencies where stale events should not be replayed.

## API Endpoints

### GET /api/v1/admin/automation/health

Get the automation engine health dashboard.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Response 200**
```json
{
  "success": true,
  "data": {
    "rules_active": 8,
    "rules_simulating": 1,
    "rules_inactive": 3,
    "actions_today": 284,
    "actions_this_week": 1842,
    "manual_actions_saved_estimate": 18420,
    "exceptions_raised_today": 2,
    "pending_approvals": 3,
    "kill_switch_status": "ACTIVE",
    "last_kill_switch_change": {
      "action": "RESUME",
      "changed_by": "admin@nammamedmate.in",
      "changed_at": "2026-07-20T14:00:00Z",
      "reason": "Incident resolved. Resuming automation."
    },
    "data_as_of": "2026-07-24T09:50:00Z"
  },
  "meta": {}
}
```

---

### POST /api/v1/admin/automation/kill-switch

Pause or resume all automation globally.

**Auth**: Bearer JWT - `admin_super` only

**Request Body**
```json
{
  "action": "PAUSE",
  "reason": "Runaway payout automation detected. Pausing all rules while investigating."
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "kill_switch_status": "PAUSED",
    "action": "PAUSE",
    "executed_by": "uuid-admin-1",
    "executed_at": "2026-07-24T09:52:00Z",
    "reason": "Runaway payout automation detected. Pausing all rules while investigating.",
    "estimated_effect_within_seconds": 60
  },
  "meta": {}
}
```

**Error Table**

| HTTP Code | Error Code | Condition |
|-----------|-----------|-----------|
| 403 | FORBIDDEN | Non-admin_super role |
| 409 | ALREADY_IN_STATE | Kill switch already PAUSED/ACTIVE |
| 400 | REASON_REQUIRED | reason is empty |

---

### GET /api/v1/admin/automation/health/per-rule

Get per-rule health metrics for the last 24 hours.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Response 200**
```json
{
  "success": true,
  "data": {
    "rules": [
      {
        "rule_id": "uuid-rule-1",
        "name": "Auto-assign unassigned orders",
        "status": "ACTIVE",
        "fire_count_24h": 48,
        "success_rate_pct": 97.9,
        "exception_count_24h": 1,
        "last_error": "Dispatch API timeout after 5s",
        "last_error_at": "2026-07-24T06:42:00Z",
        "avg_execution_ms": 387,
        "last_fired_at": "2026-07-24T09:07:00Z"
      },
      {
        "rule_id": "uuid-rule-2",
        "name": "Auto-release due payouts",
        "status": "ACTIVE",
        "fire_count_24h": 12,
        "success_rate_pct": 100.0,
        "exception_count_24h": 0,
        "last_error": null,
        "last_error_at": null,
        "avg_execution_ms": 842,
        "last_fired_at": "2026-07-24T07:00:00Z"
      }
    ]
  },
  "meta": {}
}
```

---

### GET /api/v1/admin/automation/health/circuit-breakers

Get circuit breaker status per action type.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Response 200**
```json
{
  "success": true,
  "data": {
    "circuit_breakers": [
      {
        "action_type": "auto_assign_rider",
        "fires_last_hour": 24,
        "threshold": 50,
        "circuit_status": "CLOSED",
        "opened_at": null,
        "reset_at": null
      },
      {
        "action_type": "apply_wallet_credit",
        "fires_last_hour": 52,
        "threshold": 50,
        "circuit_status": "OPEN",
        "opened_at": "2026-07-24T09:30:00Z",
        "reset_at": "2026-07-24T10:00:00Z"
      }
    ]
  },
  "meta": {}
}
```

---

## Data Models

### automation_kill_switch_log

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| action | VARCHAR(10) | PAUSE, RESUME |
| changed_by | UUID | FK ? admin_users |
| reason | TEXT | |
| changed_at | TIMESTAMPTZ | |

### automation_circuit_breakers

| Column | Type | Notes |
|--------|------|-------|
| action_type | VARCHAR(60) | PK |
| threshold_per_hour | INTEGER | Default 50 |
| circuit_status | VARCHAR(10) | CLOSED, OPEN |
| fires_last_hour | INTEGER | Rolling count (updated each fire) |
| opened_at | TIMESTAMPTZ | Nullable |
| reset_at | TIMESTAMPTZ | Nullable; opened_at + 30 minutes |
| updated_at | TIMESTAMPTZ | |

### automation_health_config

| Column | Type | Notes |
|--------|------|-------|
| key | VARCHAR(60) | PK |
| value | TEXT | Config value |
| updated_by | UUID | FK ? admin_users |
| updated_at | TIMESTAMPTZ | |

*Keys: kill_switch_status, dedup_window_seconds, approval_expiry_hours, circuit_reset_minutes*

## Acceptance Criteria

1. **AC-001**: POST /kill-switch with `action: PAUSE` immediately changes `kill_switch_status` to PAUSED; subsequent rule evaluations are halted within 60 seconds.
2. **AC-002**: POST /kill-switch by an `admin_operations` user returns `403 FORBIDDEN`.
3. **AC-003**: POST /kill-switch with empty reason returns `400 REASON_REQUIRED`.
4. **AC-004**: GET /health returns `kill_switch_status: PAUSED` while paused and `ACTIVE` after resume.
5. **AC-005**: GET /health/circuit-breakers shows `circuit_status: OPEN` for `apply_wallet_credit` when fires exceed threshold; `reset_at` is 30 minutes after `opened_at`.
6. **AC-006**: When a circuit is OPEN for an action type, all rules attempting that action log `EXCEPTION` with message `CIRCUIT_OPEN` in the activity log.
7. **AC-007**: GET /health/per-rule returns `success_rate_pct` computed as `EXECUTED / (EXECUTED + EXCEPTION) - 100` for each rule.
8. **AC-008**: Kill switch PAUSE and RESUME events are immutably logged in `automation_kill_switch_log` with actor and reason.
9. **AC-009**: Pending approvals are unaffected by kill switch PAUSE; they can still be approved/rejected and the approved action executes upon RESUME.

## Dependencies

| Dependency | Type | Notes |
|-----------|------|-------|
| EPIC-019-STORY-001 | Integration | Kill switch check in evaluation loop |
| EPIC-019-STORY-005 | Data source | Activity log for health stats |
| EPIC-019-STORY-006 | Data source | Pending approvals count |
| Redis | Infrastructure | Circuit breaker sliding window counters |

## Notes

- The kill switch is the LAST RESORT. The recommended escalation path is: disable the specific rule first ? check circuit breakers ? only use kill switch if multiple rules are misbehaving simultaneously.
- The kill switch status is stored in Redis (for sub-second read latency) and synced to `automation_health_config` database table for persistence across restarts.
- Circuit breaker threshold defaults (50 fires/hour per action type) are conservative. They should be tuned based on 30-day production baseline after launch.
