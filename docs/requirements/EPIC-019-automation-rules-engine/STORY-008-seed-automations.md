# STORY-008: Seed Automations

| Field | Value |
|-------|-------|
| Story ID | EPIC-019-STORY-008 |
| Epic | EPIC-019 Automation and Rules Engine |
| Title | Seed Automations |
| Priority | P1 |
| Status | In Development |
| Role | admin_super |
| Last Updated | 2026-07-24 |

## Overview

The Seed Automations story documents the six pre-built automation rules and three seed workflows shipped with the Namma MedMate platform. These are created during platform initialization via an idempotent seed endpoint and represent the most critical day-one automation use cases: order assignment, payout release, invoice dunning, ticket escalation, pharmacy health recovery, and Schedule X compliance flagging. All seed rules are created in INACTIVE status and must be reviewed, simulated, and manually enabled by an admin before they take effect.

## User Roles

| Role | Access |
|------|--------|
| admin_super | View seed rules status; run initialization; enable after simulation |
| admin_operations | View seed rules status; cannot initialize |

## Business Rules

1. **Created INACTIVE**: All seed rules and workflows are created with `status: INACTIVE`. They must be reviewed, simulated, and explicitly activated by admin_super before going live.
2. **Idempotent Initialization**: Running POST /seed-rules/initialize twice is safe - already-existing seed rules are not re-created or reset. The endpoint returns the current status of each seed rule.
3. **Cannot Delete Seed Rules**: Seed rules have `is_seed_rule: true`. They cannot be deleted (only disabled). This prevents accidental loss of operational-critical automations.
4. **Can Edit Seed Rules**: Seed rules are regular rules with an `is_seed_rule` tag. They can be edited (conditions, actions, guardrails) to adapt to the platform's operational context. Editing resets status to INACTIVE.
5. **Recommended Simulation Sequence**: Before enabling any seed rule, the admin should: (1) set status to SIMULATING, (2) run 24 hours in simulation, (3) review simulation results in activity log, (4) run batch simulation (POST /simulate), (5) review estimated impact, (6) set status to ACTIVE.
6. **Seed Workflows**: Three seed workflows (DUNNING_LADDER, PHARMACY_ONBOARDING, WIN_BACK) are also created on initialization alongside the 6 seed rules.
7. **Guardrail Defaults**: Seed rule guardrails are set to conservative defaults (low value caps, strict rate limits) to minimize risk until adjusted by operations team.
8. **Seed Rule Documentation**: Each seed rule has an `expected_impact` and `edge_cases` field in its configuration description (stored as rule `description` field) to guide the admin during review.
9. **Initialization Trigger**: Platform initialization runs POST /seed-rules/initialize automatically as part of the deployment bootstrap script. Admins can also trigger it manually.
10. **Rule 6 (Schedule X) - Always Fires**: The AUTO_FLAG_SCHEDULE_X seed rule has `condition: always` (no condition filtering). It fires on every Schedule X sale, no exceptions, because compliance flagging is mandatory.

## Seed Rules Specification

### Rule 1: AUTO_ASSIGN_UNASSIGNED_ORDERS

| Field | Value |
|-------|-------|
| Trigger | `order_unassigned` with `duration_minutes: 5` |
| Conditions | `zone.coverage_status != NO_RIDERS` |
| Actions | `auto_assign_rider` |
| Guardrails | `rate_limit: { max_fires: 60, per_minutes: 60 }` per zone |
| Expected Impact | Automatically dispatches ~80% of unassigned orders within 5 minutes; reduces manual dispatch load |
| Edge Cases | If zone has no available riders (NO_RIDERS status), rule does not fire - human intervention needed |

### Rule 2: AUTO_RELEASE_DUE_PAYOUTS

| Field | Value |
|-------|-------|
| Trigger | `payout_cycle_reached` |
| Conditions | `payout.amount_paise < 5000000` (< Rs 50,000) |
| Actions | `release_payout` |
| Guardrails | `value_cap: 5000000` (Rs 50,000); `require_approval_above: 5000000` |
| Expected Impact | Automates ~90% of pharmacy and rider payouts; only high-value payouts need approval |
| Edge Cases | Failed KYC or inactive fund accounts cause payout to fail; activity log error for human review |

### Rule 3: AUTO_DUNNING_OVERDUE_INVOICES

| Field | Value |
|-------|-------|
| Type | Workflow (DUNNING_LADDER) |
| Trigger | `invoice_overdue` |
| Conditions | `invoice.status = OVERDUE` |
| Workflow Steps | Day 0: Send reminder ? Wait 72h ? Day 3: Send second reminder ? Wait 96h ? Day 7: Final warning + CSM task ? Wait 168h ? Day 14: Auto-suspend account |
| Expected Impact | Systematic dunning reduces overdue invoice tail by ~40%; avoids manual CSM follow-up |
| Edge Cases | Pharmacy pays between steps - invoice.status changes to PAID, BRANCH step detects payment, cancels execution |

### Rule 4: AUTO_ESCALATE_BREACHED_TICKETS

| Field | Value |
|-------|-------|
| Trigger | `ticket_sla_breaching` with `breach_at_minus_minutes: 5` |
| Conditions | `ticket.sla_level` in `[L1, L2, L3]` |
| Actions | `escalate_ticket`, `send_notification` (push to admin_support team) |
| Guardrails | `rate_limit: { max_fires: 50, per_minutes: 60 }` |
| Expected Impact | Ensures no ticket breaches SLA without escalation notification; targets 0% undetected SLA breaches |
| Edge Cases | Ticket resolved before escalation (5-minute window) - duplicate escalation avoided via dedup logic |

### Rule 5: AUTO_SAVE_PLAY_HEALTH_DROP

| Field | Value |
|-------|-------|
| Trigger | `health_score_drop` with `below_value: 40` |
| Conditions | `pharmacy.plan_tier` in `[STARTER, RETAIL_PRO, ENTERPRISE]` |
| Actions | `open_csm_task` (priority: HIGH), `send_notification` (WhatsApp to pharmacy owner) |
| Guardrails | `rate_limit: { max_fires: 1, per_minutes: 10080 }` (max 1 per week per pharmacy) |
| Expected Impact | Triggers CSM outreach for at-risk pharmacies; reduces churn by surfacing health drops proactively |
| Edge Cases | Free plan pharmacies excluded (no CSM coverage); rate limit prevents spam on repeatedly dipping pharmacies |

### Rule 6: AUTO_FLAG_SCHEDULE_X

| Field | Value |
|-------|-------|
| Trigger | `schedule_x_sale` |
| Conditions | Always (no conditions - all Schedule X sales must be flagged) |
| Actions | `flag_prescription`, `send_notification` (WhatsApp to compliance_team), `open_csm_task` |
| Guardrails | None (required for compliance) |
| Expected Impact | 100% Schedule X sale flagging; supports Drug Inspector audit requirement |
| Edge Cases | Duplicate sale events (retry storms) - dedup_window prevents double-flagging the same sale |

## API Endpoints

### GET /api/v1/admin/automation/seed-rules

List all seed rules with their current configuration and status.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Response 200**
```json
{
  "success": true,
  "data": {
    "seed_rules": [
      {
        "rule_id": "uuid-seed-1",
        "seed_rule_key": "AUTO_ASSIGN_UNASSIGNED_ORDERS",
        "name": "Auto-Assign Unassigned Orders",
        "status": "INACTIVE",
        "fire_count": 0,
        "simulation_run": false,
        "last_simulated_at": null,
        "simulation_risk": null,
        "recommended_next_step": "RUN_SIMULATION"
      },
      {
        "rule_id": "uuid-seed-6",
        "seed_rule_key": "AUTO_FLAG_SCHEDULE_X",
        "name": "Auto-Flag Schedule X Sales",
        "status": "ACTIVE",
        "fire_count": 48,
        "simulation_run": true,
        "last_simulated_at": "2026-01-02T14:00:00Z",
        "simulation_risk": "LOW",
        "recommended_next_step": null
      }
    ],
    "seed_workflows": [
      {
        "workflow_id": "uuid-wf-seed-1",
        "seed_workflow_key": "DUNNING_LADDER",
        "name": "Invoice Dunning Ladder",
        "status": "INACTIVE",
        "active_executions": 0
      }
    ],
    "initialized_at": "2026-01-01T00:00:00Z"
  },
  "meta": {}
}
```

---

### POST /api/v1/admin/automation/seed-rules/initialize

Idempotent initialization of all seed rules and workflows.

**Auth**: Bearer JWT - `admin_super`

**Request Body**
```json
{}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "created": 0,
    "already_existed": 6,
    "workflows_created": 0,
    "workflows_already_existed": 3,
    "rules": [
      { "seed_rule_key": "AUTO_ASSIGN_UNASSIGNED_ORDERS", "rule_id": "uuid-seed-1", "result": "ALREADY_EXISTS" },
      { "seed_rule_key": "AUTO_RELEASE_DUE_PAYOUTS", "rule_id": "uuid-seed-2", "result": "ALREADY_EXISTS" },
      { "seed_rule_key": "AUTO_DUNNING_OVERDUE_INVOICES", "rule_id": null, "result": "USES_WORKFLOW", "workflow_id": "uuid-wf-seed-1" },
      { "seed_rule_key": "AUTO_ESCALATE_BREACHED_TICKETS", "rule_id": "uuid-seed-4", "result": "ALREADY_EXISTS" },
      { "seed_rule_key": "AUTO_SAVE_PLAY_HEALTH_DROP", "rule_id": "uuid-seed-5", "result": "ALREADY_EXISTS" },
      { "seed_rule_key": "AUTO_FLAG_SCHEDULE_X", "rule_id": "uuid-seed-6", "result": "ALREADY_EXISTS" }
    ],
    "initialized_at": "2026-07-24T10:00:00Z"
  },
  "meta": {}
}
```

**Error Table**

| HTTP Code | Error Code | Condition |
|-----------|-----------|-----------|
| 403 | FORBIDDEN | Non-admin_super role |

---

## Data Models

The seed rules use the same `automation_rules` and `automation_workflows` tables as regular rules. An additional lookup table tracks seed rule metadata:

### automation_seed_rule_catalog

| Column | Type | Notes |
|--------|------|-------|
| seed_rule_key | VARCHAR(60) | PK - e.g., AUTO_ASSIGN_UNASSIGNED_ORDERS |
| rule_id | UUID | FK ? automation_rules (nullable if workflow) |
| workflow_id | UUID | FK ? automation_workflows (nullable if rule) |
| display_order | SMALLINT | Order in the UI list |
| expected_impact | TEXT | Human-readable impact description |
| edge_cases | TEXT | Known edge cases and handling |
| initialized_at | TIMESTAMPTZ | When first created by initialize endpoint |

## Acceptance Criteria

1. **AC-001**: POST /seed-rules/initialize on a fresh platform creates 6 seed rules and 3 seed workflows, all with `status: INACTIVE`.
2. **AC-002**: POST /seed-rules/initialize called a second time returns `result: ALREADY_EXISTS` for all rules without modifying them.
3. **AC-003**: GET /seed-rules returns `simulation_risk` for each seed rule that has been simulated; `null` for those not yet simulated.
4. **AC-004**: Attempting to DELETE a seed rule returns `403 FORBIDDEN`.
5. **AC-005**: AUTO_FLAG_SCHEDULE_X seed rule has `conditions: []` (empty - always fires) and cannot have conditions added that would prevent it from firing on any Schedule X sale.
6. **AC-006**: AUTO_RELEASE_DUE_PAYOUTS seed rule has `guardrails.value_cap = 5000000` (Rs 50,000) and `require_approval_above = 5000000` out of the box.
7. **AC-007**: Platform bootstrap script successfully calls POST /initialize and logs the initialization result.
8. **AC-008**: `recommended_next_step` returns `RUN_SIMULATION` for rules with `simulation_run: false` and `null` for rules that are already ACTIVE.

## Dependencies

| Dependency | Type | Notes |
|-----------|------|-------|
| EPIC-019-STORY-002 | API | Seed rules created via Rule CRUD API |
| EPIC-019-STORY-003 | API | DUNNING_LADDER workflow created via Workflow API |
| EPIC-019-STORY-004 | Process | Simulation must be run before enabling |
| Platform bootstrap script | Infrastructure | Calls initialize endpoint on deployment |

## Notes

- The seed rules are documented here both as API requirements and as operational runbooks. Operations team should read this story before enabling any seed rule.
- The AUTO_FLAG_SCHEDULE_X rule must be the FIRST seed rule enabled after platform launch (compliance requirement). All others can be enabled progressively.
- After enabling all 6 seed rules, schedule a monthly review of their fire counts and success rates to tune guardrails for actual production traffic.
