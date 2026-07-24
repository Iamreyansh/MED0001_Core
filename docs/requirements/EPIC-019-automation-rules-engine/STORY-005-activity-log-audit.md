# STORY-005: Activity Log & Audit

| Field | Value |
|-------|-------|
| Story ID | EPIC-019-STORY-005 |
| Epic | EPIC-019 Automation and Rules Engine |
| Title | Activity Log & Audit |
| Priority | P1 |
| Status | In Development |
| Role | admin_super, admin_operations |
| Last Updated | 2026-07-24 |

## Overview

The Activity Log & Audit story provides a comprehensive, append-only record of every automated action, trigger fire, and human override within the automation engine. Every EXECUTED, SIMULATED, PENDING_APPROVAL, and ROLLED_BACK action is permanently logged. A real-time activity feed with filters enables the operations team to monitor automation health and investigate anomalies. A rollback endpoint allows reversing certain automated actions (suspensions, wallet credits) with a new log entry referencing the original. An automation stats endpoint surfaces health KPIs for the dashboard.

## User Roles

| Role | Access |
|------|--------|
| admin_super | Full read + rollback |
| admin_operations | Full read + rollback |
| admin_finance | Read financial action entries only |

## Business Rules

1. **Immutable Append-Only Log**: The activity log is never edited or deleted. Every action creates a new row. Rollbacks create a new `ROLLED_BACK` row referencing the original action's `action_id`. This is the source of truth for automation audits.
2. **[SIMULATED] Prefix**: Activity log entries from SIMULATING-status rules are tagged with `status: SIMULATED`. These entries are visible in the feed and filterable but represent no real-world effect.
3. **Rollback Eligibility**: Rollbacks are available for: `SUSPEND` actions (reactivates the entity), `APPLY_WALLET_CREDIT` (debits the credit back). Rollbacks are NOT available for: `RELEASE_PAYOUT`, `PROCESS_REFUND` (use Finance module), `SEND_NOTIFICATION` (cannot unsend - log only).
4. **Rollback Chain**: Each rollback creates a new activity log entry with `action_type: ROLLBACK`, `references_action_id: original_action_id`. The original entry is not modified; it gains a `rolled_back: true` derived flag when the log is queried.
5. **Real-Time Refresh**: The activity feed is designed for real-time monitoring. The frontend polls the GET /activity endpoint every 10 seconds or subscribes to a WebSocket event channel (if WebSocket support is implemented).
6. **Filter Performance**: Activity log queries must return within 500ms. The `triggered_at` column is indexed with a composite index covering `(rule_id, triggered_at)` and `(entity_type, entity_id, triggered_at)`.
7. **Stats Computation**: `manual_actions_saved_estimate` is computed as: count of EXECUTED actions where `actor = AUTOMATION` over the last 30 days. This measures how many human decisions were automated.
8. **2-Year Retention**: Activity log entries are retained for 2 years. After 2 years, they are archived to cold storage (S3 Glacier). The API returns from live tables only; archive queries require a separate process.
9. **Entity Before/After State**: The detail endpoint (GET /activity/:action_id) includes `before_state` and `after_state` snapshots of the affected entity at the time of the action. These snapshots are captured during execution and stored as JSONB.
10. **Exception Raised**: When an action fails or a guardrail is triggered, an `EXCEPTION` entry is created in the activity log. `exceptions_raised` stat is the count of such entries in the last 24 hours.

## API Endpoints

### GET /api/v1/admin/automation/activity

Paginated activity feed of all automation actions.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Query Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| status | string | No | EXECUTED, SIMULATED, PENDING_APPROVAL, APPROVED, REJECTED, ROLLED_BACK |
| rule_id | UUID | No | Filter by rule |
| trigger_category | string | No | ORDERS, DISPATCH, PHARMACY, etc. |
| entity_type | string | No | ORDER, PHARMACY, RIDER, CUSTOMER |
| date_from | string | No | ISO 8601 |
| date_to | string | No | ISO 8601 |
| page | integer | No | Default 1 |
| limit | integer | No | Default 20 |

**Response 200**
```json
{
  "success": true,
  "data": {
    "activity": [
      {
        "action_id": "uuid-act-1",
        "rule_name": "Auto-assign unassigned orders",
        "trigger_event": "order_unassigned",
        "entity_type": "ORDER",
        "entity_id": "uuid-order-99",
        "entity_name": "ORD-8821 (Ravi Kumar)",
        "action_type": "auto_assign_rider",
        "action_params": { "order_id": "uuid-order-99" },
        "status": "EXECUTED",
        "actor": "AUTOMATION",
        "triggered_at": "2026-07-24T08:07:00Z",
        "executed_at": "2026-07-24T08:07:01Z",
        "override_by": null,
        "rolled_back": false
      },
      {
        "action_id": "uuid-act-2",
        "rule_name": "Auto-release due payouts",
        "trigger_event": "payout_cycle_reached",
        "entity_type": "PHARMACY",
        "entity_id": "uuid-ph-1",
        "entity_name": "Apollo Pharmacy - Indiranagar",
        "action_type": "release_payout",
        "action_params": { "amount_paise": 4200000 },
        "status": "PENDING_APPROVAL",
        "actor": "AUTOMATION",
        "triggered_at": "2026-07-24T07:00:00Z",
        "executed_at": null,
        "override_by": null,
        "rolled_back": false
      }
    ]
  },
  "meta": { "page": 1, "limit": 20, "total": 8420 }
}
```

---

### GET /api/v1/admin/automation/activity/:action_id

Get full detail for a single automation action including before/after entity state.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Response 200**
```json
{
  "success": true,
  "data": {
    "action_id": "uuid-act-1",
    "rule_id": "uuid-rule-1",
    "rule_name": "Auto-assign unassigned orders",
    "trigger_event_id": "uuid-tev-1",
    "trigger_event": {
      "trigger_id": "order_unassigned",
      "entity_type": "ORDER",
      "entity_id": "uuid-order-99",
      "payload": { "minutes_unassigned": 7, "zone_id": "uuid-zone-1" },
      "fired_at": "2026-07-24T08:07:00Z"
    },
    "conditions_evaluated": [
      { "field": "zone.coverage_status", "operator": "not_eq", "value": "NO_RIDERS", "resolved_value": "ADEQUATE", "result": true }
    ],
    "action_type": "auto_assign_rider",
    "action_params": { "order_id": "uuid-order-99" },
    "before_state": { "order_status": "PLACED", "rider_id": null },
    "after_state": { "order_status": "ACCEPTED", "rider_id": "uuid-rider-8" },
    "status": "EXECUTED",
    "actor": "AUTOMATION",
    "triggered_at": "2026-07-24T08:07:00Z",
    "executed_at": "2026-07-24T08:07:01Z",
    "execution_ms": 420,
    "rolled_back": false,
    "rollback_action_id": null
  },
  "meta": {}
}
```

---

### POST /api/v1/admin/automation/activity/:action_id/rollback

Rollback a reversible automated action.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Path Parameters**

| Parameter | Type | Description |
|-----------|------|-------------|
| action_id | UUID | Action ID to roll back |

**Request Body**
```json
{
  "reason": "Rule misconfigured; suspension was not warranted. Reviewing rule before re-enabling."
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "rollback_action_id": "uuid-act-rollback-1",
    "original_action_id": "uuid-act-suspend-1",
    "action_type": "ROLLBACK",
    "rolled_back_action": "suspend_entity",
    "entity_type": "PHARMACY",
    "entity_id": "uuid-ph-5",
    "result": "Entity reactivated successfully.",
    "executed_at": "2026-07-24T09:40:00Z"
  },
  "meta": {}
}
```

**Error Table**

| HTTP Code | Error Code | Condition |
|-----------|-----------|-----------|
| 404 | ACTION_NOT_FOUND | action_id does not exist |
| 422 | NOT_ROLLBACKABLE | Action type is not rollbackable (e.g., release_payout) |
| 422 | ALREADY_ROLLED_BACK | Action has already been rolled back |

---

### GET /api/v1/admin/automation/activity/stats

Automation health statistics.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Response 200**
```json
{
  "success": true,
  "data": {
    "rules_active": 8,
    "rules_simulating": 1,
    "rules_inactive": 3,
    "actions_last_24h": 284,
    "actions_this_week": 1842,
    "manual_actions_saved_estimate": 1842,
    "exceptions_raised_today": 2,
    "pending_approvals_count": 3,
    "last_action_at": "2026-07-24T09:38:00Z"
  },
  "meta": {}
}
```

---

## Data Models

### automation_activity_log

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| rule_id | UUID | FK ? automation_rules (nullable for workflow steps) |
| workflow_execution_id | UUID | FK ? workflow_executions (nullable) |
| trigger_event_id | UUID | FK ? trigger_events |
| entity_type | VARCHAR(30) | |
| entity_id | UUID | |
| action_type | VARCHAR(60) | |
| action_params | JSONB | |
| conditions_evaluated | JSONB | Array of condition evaluation results |
| before_state | JSONB | Entity state snapshot before action |
| after_state | JSONB | Entity state snapshot after action |
| status | VARCHAR(20) | EXECUTED, SIMULATED, PENDING_APPROVAL, APPROVED, REJECTED, ROLLED_BACK, RATE_LIMITED, DUPLICATE_SKIPPED, EXCEPTION |
| actor | VARCHAR(15) | AUTOMATION, HUMAN |
| override_by | UUID | FK ? admin_users (nullable) |
| triggered_at | TIMESTAMPTZ | When trigger fired |
| executed_at | TIMESTAMPTZ | When action completed |
| execution_ms | INTEGER | Nullable |
| references_action_id | UUID | FK ? self (for rollback entries) |
| error_message | TEXT | Nullable |
| created_at | TIMESTAMPTZ | |

## Acceptance Criteria

1. **AC-001**: GET /activity returns entries filterable by status; `status: SIMULATED` entries are present and tagged with rule name + [SIMULATED] marker.
2. **AC-002**: GET /activity/:action_id returns full `before_state` and `after_state` snapshots for EXECUTED actions.
3. **AC-003**: POST /rollback for a `suspend_entity` action reactivates the entity and creates a new `ROLLBACK` activity log entry referencing the original.
4. **AC-004**: POST /rollback for a `release_payout` action returns `422 NOT_ROLLBACKABLE`.
5. **AC-005**: POST /rollback for an already-rolled-back action returns `422 ALREADY_ROLLED_BACK`.
6. **AC-006**: GET /activity/stats returns `pending_approvals_count` matching the actual count in the approvals queue.
7. **AC-007**: `manual_actions_saved_estimate` in stats equals the count of EXECUTED actions with `actor: AUTOMATION` in the last 30 days.
8. **AC-008**: Activity log entries for RATE_LIMITED fires have `status: RATE_LIMITED` and are visible in the feed (they do not silently disappear).
9. **AC-009**: Activity log query with date range filter returns within 500ms (verified by P99 monitoring).

## Dependencies

| Dependency | Type | Notes |
|-----------|------|-------|
| EPIC-019-STORY-001 | Writer | Core engine writes to activity log |
| EPIC-019-STORY-006 | Writer | Approvals queue writes PENDING_APPROVAL entries |
| EPIC-019-STORY-004 | Writer | Simulation writes SIMULATED entries |
| Database index | Infrastructure | Composite index on triggered_at, rule_id, entity_type |

## Notes

- The activity log is the single source of truth for automation audits. It should never be modified after insertion.
- `before_state` and `after_state` are shallow entity snapshots (not deep copies of all related records). For orders: `{ status, rider_id, pharmacy_id }`. For pharmacies: `{ status, plan_tier, health_score }`.
- 2-year live retention + cold archival. Querying archived data (> 2 years) is not supported via the API in V1.
