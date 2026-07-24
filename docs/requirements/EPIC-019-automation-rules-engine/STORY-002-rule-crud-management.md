# STORY-002: Rule CRUD Management

| Field | Value |
|-------|-------|
| Story ID | EPIC-019-STORY-002 |
| Epic | EPIC-019 Automation and Rules Engine |
| Title | Rule CRUD Management |
| Priority | P1 |
| Status | In Development |
| Role | admin_super, admin_operations |
| Last Updated | 2026-07-24 |

## Overview

The Rule CRUD Management story provides the admin UI API layer for creating, reading, updating, deleting, toggling, and duplicating automation rules. Rules are created in INACTIVE status and must be explicitly activated after review (and ideally simulation). Status transitions are controlled: editing an ACTIVE rule resets it to INACTIVE, requiring deliberate re-activation. Guardrails (rate limits, value caps, approval requirements) are configured per rule. The maximum platform limit is 200 active rules.

## User Roles

| Role | Access |
|------|--------|
| admin_super | Full CRUD; can enable rules to ACTIVE |
| admin_operations | Create, edit, simulate; cannot enable to ACTIVE in production |

## Business Rules

1. **INACTIVE by Default**: All newly created rules start with `status: INACTIVE`. They must be explicitly set to ACTIVE (by admin_super only) after review.
2. **Edit Resets Status**: PATCH /rules/:id (updating conditions, actions, or guardrails) on an ACTIVE rule automatically resets status to INACTIVE. The admin must re-review and re-enable.
3. **Unique Rule Names**: Rule names must be unique across the platform. Duplicate names return `409 RULE_NAME_CONFLICT`.
4. **Rate Limit Guardrail**: `guardrails.rate_limit` = `{ max_fires: N, per_minutes: M }`. The action fires at most N times per M minutes globally. Excess fires are logged as `RATE_LIMITED` in the activity log.
5. **Value Cap Guardrail**: `guardrails.value_cap` (in paise). Actions involving amounts below the cap execute automatically. Actions above the cap are routed to the approvals queue.
6. **Require Approval Guardrail**: `guardrails.require_approval_above` (in paise). When set, any action where the computed amount exceeds this value requires human approval before execution.
7. **Deletion Restriction**: Rules can only be deleted if `status: INACTIVE` AND `fire_count = 0`. Attempting to delete a fired rule returns `422 RULE_HAS_FIRE_HISTORY`. Use `force: true` to override (admin_super only); deletion is then soft (archived, not physically deleted).
8. **Simulation Status**: Status `SIMULATING` runs the rule against live events but logs actions as `[SIMULATED]` without executing them. admin_operations can set to SIMULATING; only admin_super can set to ACTIVE.
9. **Platform Cap**: Maximum 200 active rules. Attempting to activate a 201st rule returns `422 ACTIVE_RULE_LIMIT_REACHED`.
10. **Audit Logging**: Every create, update, status change, and delete is audit-logged with actor, timestamp, and diff of changed fields.

## API Endpoints

### GET /api/v1/admin/automation/rules

List all automation rules.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Query Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| status | string | No | ACTIVE, INACTIVE, SIMULATING |
| trigger_category | string | No | ORDERS, DISPATCH, PHARMACY, etc. |
| search | string | No | Search by rule name |
| page | integer | No | Default 1 |
| limit | integer | No | Default 20 |

**Response 200**
```json
{
  "success": true,
  "data": {
    "rules": [
      {
        "rule_id": "uuid-rule-1",
        "name": "Auto-assign unassigned orders",
        "trigger_id": "order_unassigned",
        "trigger_category": "DISPATCH",
        "conditions_summary": "zone.coverage_status != NO_RIDERS",
        "actions_summary": "auto_assign_rider",
        "status": "ACTIVE",
        "fire_count": 1248,
        "last_fired_at": "2026-07-24T08:07:00Z",
        "created_at": "2026-02-01T10:00:00Z",
        "is_seed_rule": true
      }
    ]
  },
  "meta": { "page": 1, "limit": 20, "total": 12 }
}
```

---

### POST /api/v1/admin/automation/rules

Create a new automation rule.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Request Body**
```json
{
  "name": "Escalate SLA-breaching tickets",
  "description": "Auto-escalate support tickets approaching SLA breach",
  "trigger_id": "ticket_sla_breaching",
  "trigger_params": {
    "breach_at_minus_minutes": 5
  },
  "conditions": [
    {
      "field": "ticket.sla_level",
      "operator": "priority_eq",
      "value": ["L1", "L2"]
    }
  ],
  "actions": [
    {
      "action_id": "escalate_ticket",
      "params": {
        "escalate_to": "L2"
      },
      "parallel": false
    },
    {
      "action_id": "send_notification",
      "params": {
        "channel": "PUSH",
        "recipient_type": "ADMIN",
        "template": "TICKET_SLA_ALERT"
      },
      "parallel": true
    }
  ],
  "guardrails": {
    "rate_limit": {
      "max_fires": 100,
      "per_minutes": 60
    },
    "require_approval_above": null
  }
}
```

**Response 201**
```json
{
  "success": true,
  "data": {
    "rule_id": "uuid-rule-2",
    "name": "Escalate SLA-breaching tickets",
    "status": "INACTIVE",
    "created_by": "uuid-admin-1",
    "created_at": "2026-07-24T01:55:00Z"
  },
  "meta": {}
}
```

**Error Table**

| HTTP Code | Error Code | Condition |
|-----------|-----------|-----------|
| 409 | RULE_NAME_CONFLICT | Rule name already exists |
| 422 | INVALID_TRIGGER | trigger_id not in registry |
| 422 | INVALID_ACTION | action_id not in registry |
| 422 | INVALID_CONDITION_OPERATOR | Operator not supported for trigger |

---

### GET /api/v1/admin/automation/rules/:id

Get rule detail with recent fire history.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Response 200**
```json
{
  "success": true,
  "data": {
    "rule_id": "uuid-rule-1",
    "name": "Auto-assign unassigned orders",
    "description": "Automatically assigns orders unassigned for > 5 minutes",
    "trigger_id": "order_unassigned",
    "trigger_params": { "duration_minutes": 5 },
    "conditions": [
      { "field": "zone.coverage_status", "operator": "not_eq", "value": "NO_RIDERS" }
    ],
    "actions": [
      { "action_id": "auto_assign_rider", "params": { "priority_override": null }, "parallel": false }
    ],
    "guardrails": {
      "rate_limit": { "max_fires": 60, "per_minutes": 60 },
      "value_cap": null,
      "require_approval_above": null
    },
    "status": "ACTIVE",
    "fire_count": 1248,
    "last_fired_at": "2026-07-24T08:07:00Z",
    "is_seed_rule": true,
    "created_by": "SYSTEM",
    "created_at": "2026-01-01T00:00:00Z",
    "recent_fires": [
      { "fired_at": "2026-07-24T08:07:00Z", "entity_id": "uuid-order-99", "result": "EXECUTED" },
      { "fired_at": "2026-07-24T07:52:00Z", "entity_id": "uuid-order-98", "result": "EXECUTED" }
    ]
  },
  "meta": {}
}
```

---

### PATCH /api/v1/admin/automation/rules/:id

Update rule configuration (resets to INACTIVE if was ACTIVE).

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Request Body** (partial update)
```json
{
  "conditions": [
    { "field": "zone.coverage_status", "operator": "not_eq", "value": "NO_RIDERS" },
    { "field": "order.priority", "operator": "priority_eq", "value": "HIGH" }
  ]
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "rule_id": "uuid-rule-1",
    "status": "INACTIVE",
    "status_reset_reason": "RULE_EDITED",
    "updated_at": "2026-07-24T09:00:00Z"
  },
  "meta": {}
}
```

---

### PATCH /api/v1/admin/automation/rules/:id/status

Set rule status.

**Auth**: Bearer JWT - `admin_super` (ACTIVE); `admin_operations` (SIMULATING, INACTIVE)

**Request Body**
```json
{
  "status": "ACTIVE"
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "rule_id": "uuid-rule-1",
    "previous_status": "INACTIVE",
    "new_status": "ACTIVE",
    "updated_by": "uuid-admin-1",
    "updated_at": "2026-07-24T09:05:00Z"
  },
  "meta": {}
}
```

**Error Table**

| HTTP Code | Error Code | Condition |
|-----------|-----------|-----------|
| 403 | FORBIDDEN | admin_operations attempting to set ACTIVE |
| 422 | ACTIVE_RULE_LIMIT_REACHED | 200 active rules already |

---

### DELETE /api/v1/admin/automation/rules/:id

Delete a rule (only if INACTIVE and fire_count = 0, or force=true for admin_super).

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Query Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| force | boolean | No | admin_super only; soft-delete even with fire history |

**Response 200**
```json
{
  "success": true,
  "data": {
    "deleted": true,
    "rule_id": "uuid-rule-5",
    "deleted_at": "2026-07-24T09:10:00Z"
  },
  "meta": {}
}
```

**Error Table**

| HTTP Code | Error Code | Condition |
|-----------|-----------|-----------|
| 403 | FORBIDDEN | Attempt to delete seed rule |
| 422 | RULE_HAS_FIRE_HISTORY | fire_count > 0 without force |
| 422 | RULE_IS_ACTIVE | Rule is ACTIVE; must deactivate first |

---

### POST /api/v1/admin/automation/rules/:id/duplicate

Clone a rule (creates copy with INACTIVE status and "(Copy)" suffix on name).

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Response 201**
```json
{
  "success": true,
  "data": {
    "new_rule_id": "uuid-rule-new",
    "name": "Auto-assign unassigned orders (Copy)",
    "status": "INACTIVE",
    "created_at": "2026-07-24T09:12:00Z"
  },
  "meta": {}
}
```

---

## Data Models

### automation_rules

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| name | VARCHAR(200) | Unique |
| description | TEXT | |
| trigger_id | VARCHAR(60) | FK ? trigger_registry |
| trigger_params | JSONB | Trigger parameter values |
| conditions | JSONB | Array of condition objects |
| actions | JSONB | Array of action objects |
| guardrails | JSONB | rate_limit, value_cap, require_approval_above |
| status | VARCHAR(15) | ACTIVE, INACTIVE, SIMULATING |
| fire_count | INTEGER | Total fires since creation |
| last_fired_at | TIMESTAMPTZ | Nullable |
| is_seed_rule | BOOLEAN | Seed rules cannot be deleted |
| dedup_window_seconds | INTEGER | Default 300 |
| created_by | UUID | FK ? admin_users (null for SYSTEM) |
| created_at | TIMESTAMPTZ | |
| updated_at | TIMESTAMPTZ | |
| deleted_at | TIMESTAMPTZ | Nullable; soft delete |

## Acceptance Criteria

1. **AC-001**: POST /rules creates a rule with `status: INACTIVE` regardless of what the caller sends for status.
2. **AC-002**: PATCH /rules/:id on an ACTIVE rule resets status to INACTIVE and returns `status_reset_reason: RULE_EDITED`.
3. **AC-003**: PATCH /rules/:id/status with `status: ACTIVE` by an `admin_operations` user returns `403 FORBIDDEN`.
4. **AC-004**: PATCH /rules/:id/status with ACTIVE when 200 rules are already active returns `422 ACTIVE_RULE_LIMIT_REACHED`.
5. **AC-005**: DELETE /rules/:id for a rule with fire_count > 0 without `?force=true` returns `422 RULE_HAS_FIRE_HISTORY`.
6. **AC-006**: DELETE /rules/:id for a seed rule (`is_seed_rule: true`) returns `403 FORBIDDEN` even with force=true.
7. **AC-007**: POST /rules/:id/duplicate creates a new rule with `status: INACTIVE`, `fire_count: 0`, and name suffixed with "(Copy)".
8. **AC-008**: GET /rules with `?status=ACTIVE&trigger_category=DISPATCH` returns only ACTIVE rules in the DISPATCH category.
9. **AC-009**: Rule name uniqueness is enforced; POST /rules with an existing name returns `409 RULE_NAME_CONFLICT`.

## Dependencies

| Dependency | Type | Notes |
|-----------|------|-------|
| EPIC-019-STORY-001 | Core engine | Trigger and action registry |
| EPIC-019-STORY-007 | Health | Kill switch integration |
| EPIC-019-STORY-005 | Audit | Rule change audit logging |

## Notes

- Guardrail `rate_limit` uses a sliding window counter stored in Redis for low-latency checks.
- The `conditions` and `actions` JSONB fields are validated against a schema on save (not just at evaluation time) to catch configuration errors early.
- Future: Support OR logic in conditions via condition groups (`{ "operator": "OR", "conditions": [...] }`).
