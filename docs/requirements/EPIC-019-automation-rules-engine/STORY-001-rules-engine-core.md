# STORY-001: Rules Engine Core

| Field | Value |
|-------|-------|
| Story ID | EPIC-019-STORY-001 |
| Epic | EPIC-019 Automation and Rules Engine |
| Title | Rules Engine Core |
| Priority | P1 |
| Status | In Development |
| Role | admin_super, admin_operations (configuration); Internal (execution) |
| Last Updated | 2026-07-24 |

## Overview

The Rules Engine Core story defines the foundational infrastructure of the Namma MedMate Automation Engine. It implements the trigger registry (catalog of all event types that can fire rules), the condition evaluator (AND-logic condition chain assessment), and the action executor (dispatching and tracking automated actions). The engine is event-driven: services publish events to a message queue, the engine subscribes, matches against active rules, evaluates conditions, and dispatches actions. Discovery endpoints expose all available triggers and actions to the admin UI for rule building.

## User Roles

| Role | Access |
|------|--------|
| admin_super | View triggers and actions catalog; trigger manual evaluation |
| admin_operations | View triggers and actions catalog |
| Internal services | Publish trigger events (service-to-service) |
| Rules engine | Internal execution (no user-facing role) |

## Business Rules

1. **Event-Driven Architecture**: The rules engine is a consumer on the internal message queue (SQS or Redis Streams). Every service (order, payment, CRM, etc.) publishes `TriggerEvent` messages when relevant actions occur. The engine processes these asynchronously.
2. **Trigger Matching**: On receiving a `TriggerEvent`, the engine fetches all ACTIVE rules whose `trigger_id` matches the event's `trigger_id`. Rules are fetched from a cached in-memory registry (refreshed every 30 seconds).
3. **Condition AND Logic**: All conditions in a rule's `conditions` array must evaluate to true for the rule to fire. If any condition fails, the rule does not fire. There is no OR logic in V1 (OR can be implemented via separate rules).
4. **Idempotency**: Each rule maintains a `dedup_window_seconds` (default 300 = 5 minutes). If the same `(rule_id, entity_id)` pair fires within the dedup window, the second execution is silently skipped and logged as `DUPLICATE_SKIPPED`.
5. **Condition Operators**: `amount_gt`, `amount_lt` (compare numeric fields), `zone_in` (entity zone in set), `plan_tier_eq` (pharmacy plan), `priority_eq` (order priority), `segment_in` (customer segment), `time_of_day_between` (current time within range), `day_of_week_in`, `count_gt` (rolling count above threshold), `health_band_eq` (CRM health), `risk_score_gt`.
6. **Action Sequencing**: Actions in a rule's `actions` array are executed sequentially by default. If `parallel: true` is set on the action, it is dispatched concurrently with others. Action failures log an error but do not stop subsequent actions.
7. **Evaluation SLA**: The entire trigger receipt ? condition evaluation ? action dispatch pipeline must complete within 500ms (P99). Action execution itself is async and not included in this SLA.
8. **Available Trigger Categories**: Orders (order_placed, order_accepted, order_stuck_in_stage, sla_breaching, order_cancelled, order_delivered), Dispatch (order_unassigned, rider_no_show, rider_went_offline_mid_trip), Pharmacy (kyc_submitted, fill_rate_below_threshold, storefront_offline_in_peak_hours, payout_due), Rider (kyc_submitted, on_time_pct_drop, cod_in_hand_above_limit), Finance (payout_cycle_reached, payment_failed, refund_queued, invoice_overdue), CRM (health_score_drop, near_seat_cap, trial_ending, renewal_approaching, usage_dip), Support (ticket_created, sla_breaching, negative_csat), Compliance (rx_uploaded, schedule_x_sale, register_due), Growth (coupon_budget_exhausted, campaign_underperforming, segment_threshold_crossed).
9. **Available Action Types**: auto_assign_rider, auto_reassign_rider, release_payout, process_refund, send_notification, escalate_ticket, apply_wallet_credit, suspend_entity, reactivate_entity, change_plan, open_csm_task, page_human, set_feature_flag, update_order_status.
10. **Kill Switch Respect**: Before evaluating any rule, the engine checks the global `automation_kill_switch` status. If `PAUSED`, all evaluation is halted and events are acknowledged (not requeued) to prevent queue backup. Events are logged as `KILL_SWITCH_PAUSED`.

## API Endpoints

### GET /api/v1/admin/automation/triggers

List all available trigger types with metadata.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Query Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| category | string | No | Filter by trigger category |

**Response 200**
```json
{
  "success": true,
  "data": {
    "triggers": [
      {
        "trigger_id": "order_unassigned",
        "category": "DISPATCH",
        "name": "Order Unassigned",
        "description": "Fires when an order has been placed but no rider assigned for N minutes.",
        "parameters": [
          { "name": "duration_minutes", "type": "integer", "required": true, "description": "Minutes without assignment before trigger fires" }
        ],
        "available_conditions": [
          "zone_in", "time_of_day_between", "day_of_week_in"
        ],
        "available_context_vars": [
          "order.id", "order.zone_id", "order.placed_at", "order.priority"
        ]
      },
      {
        "trigger_id": "health_score_drop",
        "category": "CRM",
        "name": "Health Score Drop",
        "description": "Fires when a pharmacy's CRM health score drops below a threshold.",
        "parameters": [
          { "name": "below_value", "type": "integer", "required": true }
        ],
        "available_conditions": ["plan_tier_eq", "health_band_eq"],
        "available_context_vars": ["pharmacy.id", "pharmacy.plan_tier", "pharmacy.health_score"]
      }
    ],
    "total_triggers": 28
  },
  "meta": {}
}
```

---

### GET /api/v1/admin/automation/actions

List all available action types with metadata.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Response 200**
```json
{
  "success": true,
  "data": {
    "actions": [
      {
        "action_id": "auto_assign_rider",
        "category": "DISPATCH",
        "name": "Auto-Assign Rider",
        "description": "Automatically assigns the best available rider to the order using the dispatch algorithm.",
        "required_params": ["order_id"],
        "optional_params": ["zone_preference", "priority_override"],
        "is_reversible": false,
        "always_require_approval": false
      },
      {
        "action_id": "release_payout",
        "category": "FINANCE",
        "name": "Release Payout",
        "description": "Releases a pending payout to a pharmacy or rider bank account.",
        "required_params": ["entity_type", "entity_id", "amount_paise"],
        "optional_params": ["mode"],
        "is_reversible": false,
        "always_require_approval": false,
        "auto_approval_limit_paise": 5000000
      },
      {
        "action_id": "suspend_entity",
        "category": "ADMIN",
        "name": "Suspend Entity",
        "description": "Suspends a pharmacy or rider account.",
        "required_params": ["entity_type", "entity_id", "reason"],
        "optional_params": [],
        "is_reversible": true,
        "always_require_approval": true
      }
    ],
    "total_actions": 14
  },
  "meta": {}
}
```

---

### POST /api/v1/admin/automation/rules/evaluate

Internal endpoint: evaluate a rule against an event payload (used by event bus).

**Auth**: Service-to-service JWT (internal only)

**Request Body**
```json
{
  "rule_id": "uuid-rule-1",
  "event": {
    "trigger_id": "order_unassigned",
    "entity_type": "ORDER",
    "entity_id": "uuid-order-1",
    "payload": {
      "order_id": "uuid-order-1",
      "zone_id": "uuid-zone-1",
      "placed_at": "2026-07-24T08:00:00Z",
      "minutes_unassigned": 7
    },
    "fired_at": "2026-07-24T08:07:00Z"
  },
  "dry_run": false
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "rule_id": "uuid-rule-1",
    "conditions_met": true,
    "conditions_evaluated": [
      { "field": "zone.coverage_status", "operator": "not_eq", "value": "NO_RIDERS", "result": true }
    ],
    "actions_dispatched": [
      { "action_id": "auto_assign_rider", "status": "DISPATCHED", "activity_log_id": "uuid-act-1" }
    ],
    "duplicate_skipped": false,
    "evaluation_ms": 42
  },
  "meta": {}
}
```

---

## Data Models

### trigger_registry

| Column | Type | Notes |
|--------|------|-------|
| trigger_id | VARCHAR(60) | PK - slug |
| category | VARCHAR(20) | ORDERS, DISPATCH, PHARMACY, RIDER, FINANCE, CRM, SUPPORT, COMPLIANCE, GROWTH |
| name | VARCHAR(100) | Display name |
| description | TEXT | |
| parameters_schema | JSONB | JSON Schema for trigger params |
| available_conditions | TEXT[] | Condition operator IDs usable with this trigger |
| available_context_vars | TEXT[] | Context variables available in conditions |
| is_active | BOOLEAN | |

### action_registry

| Column | Type | Notes |
|--------|------|-------|
| action_id | VARCHAR(60) | PK - slug |
| category | VARCHAR(20) | DISPATCH, FINANCE, NOTIFICATION, ADMIN, CRM |
| name | VARCHAR(100) | |
| description | TEXT | |
| required_params_schema | JSONB | Required action parameters |
| optional_params_schema | JSONB | |
| is_reversible | BOOLEAN | |
| always_require_approval | BOOLEAN | |
| auto_approval_limit_paise | BIGINT | Nullable |

### trigger_events

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| trigger_id | VARCHAR(60) | FK ? trigger_registry |
| entity_type | VARCHAR(30) | ORDER, PHARMACY, RIDER, etc. |
| entity_id | UUID | |
| payload | JSONB | Full event payload |
| fired_at | TIMESTAMPTZ | |
| processed_at | TIMESTAMPTZ | Nullable |
| rules_evaluated | INTEGER | Count of rules evaluated |
| rules_fired | INTEGER | Count of rules that fired |

## Acceptance Criteria

1. **AC-001**: GET /triggers returns all 28+ triggers grouped by category; each entry includes `available_context_vars` and `available_conditions`.
2. **AC-002**: GET /actions returns all 14 action types; `always_require_approval: true` for suspend_entity and mass_payout actions.
3. **AC-003**: POST /rules/evaluate with a rule where conditions fail returns `conditions_met: false` and no actions dispatched.
4. **AC-004**: POST /rules/evaluate for the same rule_id + entity_id within the dedup_window returns `duplicate_skipped: true`.
5. **AC-005**: Rule evaluation completes (trigger receipt to action dispatch) within 500ms P99.
6. **AC-006**: When `automation_kill_switch` is PAUSED, POST /rules/evaluate logs `KILL_SWITCH_PAUSED` and returns without executing actions.
7. **AC-007**: Action failure (e.g., payout API error) is logged in activity log but the next action in the sequence still executes.
8. **AC-008**: The `trigger_events` table records every event the engine receives, including those where no rules matched.

## Dependencies

| Dependency | Type | Notes |
|-----------|------|-------|
| Message queue (SQS/Redis) | Infrastructure | Event bus |
| EPIC-019-STORY-002 | Rules definitions | Rule CRUD provides active rules |
| EPIC-019-STORY-007 | Safety | Kill switch check |
| All domain services | Event publishers | Order, finance, CRM, etc. |

## Notes

- The trigger and action registries are seeded during platform initialization. New triggers/actions require code deployment (not configurable via UI in V1).
- Context variable resolution uses a lightweight expression evaluator (jsonpath-like). Complex expressions (e.g., `order.zone.coverage_status`) are resolved via entity lookups at evaluation time.
- The `evaluate` endpoint is internal-only and not documented in the public API reference.
