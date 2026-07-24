# STORY-004: Rule Simulation

| Field | Value |
|-------|-------|
| Story ID | EPIC-019-STORY-004 |
| Epic | EPIC-019 Automation and Rules Engine |
| Title | Rule Simulation |
| Priority | P1 |
| Status | In Development |
| Role | admin_super, admin_operations |
| Last Updated | 2026-07-24 |

## Overview

The Rule Simulation story provides a safe testing environment for automation rules before they are activated in production. In SIMULATING status, a rule fires on real live events but does not execute actions - it only logs what would have happened. The simulate endpoint runs a rule against up to 100 historical matching events to preview impact. A live preview endpoint tests a rule against a specific entity. Simulation results include a risk assessment and estimated impact summary, enabling admins to make informed decisions before enabling.

## User Roles

| Role | Access |
|------|--------|
| admin_super | Full access; review simulation results |
| admin_operations | Full access; run simulations |

## Business Rules

1. **SIMULATING Status**: When a rule's status is set to `SIMULATING`, it participates in the live event pipeline but writes activity log entries with `status: SIMULATED` instead of executing actions. This is the primary simulation mode.
2. **Batch Simulation**: POST /simulate runs the rule retroactively against up to `sample_size` (default 100, max 1000) recent events that match the rule's trigger. This is a historical replay - no live events consumed.
3. **Simulation Does Not Execute**: In both SIMULATING status and batch simulation, zero actions are executed. All would-have-fired actions are logged as `[SIMULATED]`.
4. **24-Hour Live Simulation Cap**: A rule in SIMULATING status auto-reverts to INACTIVE after 24 hours if not manually transitioned to ACTIVE. The admin receives an email/push alert to review results before the cap.
5. **Risk Assessment**: The simulation response includes `false_positive_risk` (LOW/MEDIUM/HIGH) based on: HIGH if `actions_that_would_fire` includes irreversible actions (payouts, suspensions) for > 10% of matched entities; MEDIUM if > 5%; LOW otherwise.
6. **Impact Summary Format**: `estimated_impact_summary` is a human-readable string, e.g., "Would have fired 48 times in last 7 days, affecting 48 orders, executing auto_assign_rider for 48 orders."
7. **Live Preview**: POST /simulation-preview tests the rule against a single specific entity at the current moment. It resolves all context variables and returns a detailed would-fire analysis including which conditions passed/failed.
8. **Simulation Result Retention**: Simulation results are stored for 7 days. After 7 days, results are deleted but the activity log entries (with [SIMULATED] prefix) remain.
9. **Multiple Simulations**: Multiple simulation runs can be triggered for the same rule. Each run gets a unique `simulation_id`. Only the most recent run's result is shown in the UI by default.
10. **Admin Review Requirement**: The admin UI enforces a review step before transitioning from SIMULATING to ACTIVE: the admin must click "I've reviewed the simulation results" checkbox. This is a UX-layer enforcement (API does not block the status change).

## API Endpoints

### POST /api/v1/admin/automation/rules/:id/simulate

Run a batch simulation of the rule against recent matching historical events.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Path Parameters**

| Parameter | Type | Description |
|-----------|------|-------------|
| id | UUID | Rule ID |

**Request Body**
```json
{
  "sample_size": 100,
  "dry_run": true
}
```

**Response 202**
```json
{
  "success": true,
  "data": {
    "simulation_id": "uuid-sim-1",
    "rule_id": "uuid-rule-2",
    "status": "RUNNING",
    "sample_size": 100,
    "started_at": "2026-07-24T09:30:00Z",
    "estimated_completion_seconds": 15
  },
  "meta": {}
}
```

**Error Table**

| HTTP Code | Error Code | Condition |
|-----------|-----------|-----------|
| 400 | SAMPLE_SIZE_TOO_LARGE | sample_size > 1000 |
| 422 | RULE_IS_ACTIVE | Cannot simulate an already-ACTIVE rule |

---

### GET /api/v1/admin/automation/rules/:id/simulation-results/:simulation_id

Get results of a completed simulation run.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Response 200**
```json
{
  "success": true,
  "data": {
    "simulation_id": "uuid-sim-1",
    "rule_id": "uuid-rule-2",
    "status": "COMPLETED",
    "sample_size": 100,
    "events_scanned": 100,
    "entities_matched": 48,
    "conditions_failed_count": 52,
    "actions_that_would_fire": [
      {
        "action": "send_notification",
        "entity_id": "uuid-ph-5",
        "entity_name": "Medplus - HSR Layout",
        "action_params": { "template": "INVOICE_OVERDUE_DAY0", "channel": "WHATSAPP" },
        "would_require_approval": false
      }
    ],
    "false_positive_risk": "LOW",
    "risk_details": "All actions are reversible notifications; no financial impact.",
    "estimated_impact_summary": "Would have fired 48 times in the last 7 days, affecting 48 pharmacies, sending 48 WhatsApp notifications.",
    "completed_at": "2026-07-24T09:30:18Z"
  },
  "meta": {}
}
```

**Error Table**

| HTTP Code | Error Code | Condition |
|-----------|-----------|-----------|
| 404 | SIMULATION_NOT_FOUND | simulation_id does not exist |
| 425 | SIMULATION_STILL_RUNNING | Results not yet available |

---

### POST /api/v1/admin/automation/rules/:id/simulation-preview

Test rule against a specific entity/event at this moment.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Request Body**
```json
{
  "entity_type": "PHARMACY",
  "entity_id": "uuid-ph-5"
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "rule_id": "uuid-rule-2",
    "entity_type": "PHARMACY",
    "entity_id": "uuid-ph-5",
    "entity_name": "Medplus - HSR Layout",
    "conditions_evaluated": [
      {
        "field": "invoice.status",
        "operator": "eq",
        "value": "OVERDUE",
        "resolved_value": "OVERDUE",
        "result": true
      }
    ],
    "would_fire": true,
    "actions_that_would_fire": [
      {
        "action": "send_notification",
        "params": { "template": "INVOICE_OVERDUE_DAY0", "channel": "WHATSAPP" },
        "would_require_approval": false,
        "is_reversible": true
      }
    ],
    "would_be_rate_limited": false,
    "would_be_deduplicated": false,
    "evaluated_at": "2026-07-24T09:35:00Z"
  },
  "meta": {}
}
```

**Error Table**

| HTTP Code | Error Code | Condition |
|-----------|-----------|-----------|
| 404 | ENTITY_NOT_FOUND | entity_id not found |
| 422 | INVALID_ENTITY_TYPE | entity_type not supported by this rule's trigger |

---

## Data Models

### automation_simulations

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| rule_id | UUID | FK ? automation_rules |
| sample_size | INTEGER | Requested sample size |
| events_scanned | INTEGER | |
| entities_matched | INTEGER | |
| conditions_failed_count | INTEGER | |
| false_positive_risk | VARCHAR(10) | LOW, MEDIUM, HIGH |
| risk_details | TEXT | |
| estimated_impact_summary | TEXT | |
| results_json | JSONB | Full actions_that_would_fire array |
| status | VARCHAR(15) | RUNNING, COMPLETED, FAILED |
| started_at | TIMESTAMPTZ | |
| completed_at | TIMESTAMPTZ | Nullable |
| triggered_by | UUID | FK ? admin_users |
| expires_at | TIMESTAMPTZ | 7 days from completion |

## Acceptance Criteria

1. **AC-001**: POST /simulate returns HTTP 202 with a `simulation_id`; results are available via the GET endpoint after completion.
2. **AC-002**: POST /simulate for a rule with `status: ACTIVE` returns `422 RULE_IS_ACTIVE` (cannot batch-simulate active rules).
3. **AC-003**: GET /simulation-results returns `false_positive_risk: HIGH` when actions include `suspend_entity` or `release_payout` affecting > 10% of matched entities.
4. **AC-004**: GET /simulation-results returns `status: RUNNING` (not 404) while the simulation is still processing.
5. **AC-005**: POST /simulation-preview for a matching entity returns `would_fire: true` with `conditions_evaluated` showing each condition's `resolved_value` and `result`.
6. **AC-006**: POST /simulation-preview for an entity that fails a condition returns `would_fire: false` with the failing condition identified.
7. **AC-007**: In SIMULATING status, a rule receives live events and creates activity log entries with `status: SIMULATED` but dispatches zero real actions.
8. **AC-008**: A rule that has been in SIMULATING status for 24 hours auto-reverts to INACTIVE and the admin receives a push/email notification.
9. **AC-009**: `estimated_impact_summary` string is present and human-readable in simulation results.

## Dependencies

| Dependency | Type | Notes |
|-----------|------|-------|
| EPIC-019-STORY-001 | Core engine | Condition evaluation, context resolution |
| EPIC-019-STORY-002 | Rule status | SIMULATING status management |
| EPIC-019-STORY-005 | Audit | [SIMULATED] activity log entries |
| Background job | Infrastructure | Async batch simulation execution |

## Notes

- Batch simulation replays historical `trigger_events` from the database, not live events. This gives a realistic preview without consuming real-time queue messages.
- For high-risk rules (financial payouts, suspensions), the team should always run batch simulation AND live simulation (SIMULATING status) before enabling.
- The 24-hour auto-revert cap is configurable in admin settings but defaults to 24 hours.
