# STORY-003: Workflow / Journey Builder

| Field | Value |
|-------|-------|
| Story ID | EPIC-019-STORY-003 |
| Epic | EPIC-019 Automation and Rules Engine |
| Title | Workflow / Journey Builder |
| Priority | P1 |
| Status | In Development |
| Role | admin_super, admin_operations |
| Last Updated | 2026-07-24 |

## Overview

The Workflow / Journey Builder story extends the rules engine with multi-step sequential automation. Unlike single-fire rules, workflows are state machines that can include ACTION steps, WAIT steps (persisted async delays), and BRANCH steps (conditional routing). Workflows are ideal for multi-day sequences: dunning ladders, pharmacy onboarding journeys, and win-back campaigns. Each entity being run through a workflow has its own execution instance, allowing independent state tracking. Three seed workflows are pre-configured for the platform.

## User Roles

| Role | Access |
|------|--------|
| admin_super | Full access; enable workflows |
| admin_operations | Create, edit, view; cannot enable without admin_super |

## Business Rules

1. **Workflow vs Rule**: A workflow is a sequence of steps triggered by a single trigger event. Unlike rules (which fire once per event), workflows maintain per-entity execution state and can span days or weeks.
2. **Execution Instance**: Each time a workflow trigger fires for an entity, a new `WorkflowExecution` is created for that entity. The execution progresses through steps independently of other entities.
3. **Wait Steps**: A WAIT step pauses the execution for `wait_duration_hours`. The execution state is persisted to the database. A scheduler job wakes up executions whose wait has elapsed and moves them to the next step.
4. **Branch Steps**: A BRANCH step evaluates a condition at execution time. If the condition is true, the execution follows `next_step_id_on_true`; otherwise `next_step_id_on_false`. Conditions use the same operator set as the rules engine.
5. **Deduplication**: A maximum of 1 active execution per workflow per entity is allowed. A second trigger for the same entity while an execution is RUNNING is ignored (logged as `DUPLICATE_EXECUTION_SKIPPED`).
6. **Pausing During Edit**: When a workflow is updated (PATCH /workflows/:id), all active executions are PAUSED. After the update, an admin must manually resume executions via a bulk-resume action or cancel them.
7. **Version Control**: Each PATCH to a workflow creates a new version record. Active executions reference the version they started on. This allows the admin to see which version each execution is running.
8. **Workflow Enable**: admin_operations can create and edit workflows but only admin_super can toggle `status: ACTIVE`.
9. **Execution Cancellation**: An execution can be cancelled at any point. Cancelled executions do not continue to the next step. Any already-executed actions are not rolled back.
10. **Maximum Step Count**: A workflow may have up to 20 steps. Workflows with > 20 steps return `422 STEP_LIMIT_EXCEEDED`.

## API Endpoints

### GET /api/v1/admin/automation/workflows

List all workflows.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Response 200**
```json
{
  "success": true,
  "data": {
    "workflows": [
      {
        "id": "uuid-wf-1",
        "name": "DUNNING_LADDER",
        "description": "Progressive dunning for overdue invoices",
        "trigger_id": "invoice_overdue",
        "steps_count": 7,
        "active_executions": 4,
        "completed_today": 2,
        "status": "ACTIVE",
        "version": 1,
        "created_at": "2026-01-01T00:00:00Z"
      }
    ]
  },
  "meta": {}
}
```

---

### POST /api/v1/admin/automation/workflows

Create a new workflow.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Request Body**
```json
{
  "name": "PHARMACY_ONBOARDING",
  "description": "Onboarding journey triggered after KYC approval",
  "trigger_id": "kyc_approved",
  "steps": [
    {
      "step_id": "s1",
      "type": "ACTION",
      "action_id": "send_notification",
      "params": { "template": "KYC_APPROVED", "channel": "WHATSAPP" },
      "next_step_id_on_true": "s2",
      "next_step_id_on_false": null
    },
    {
      "step_id": "s2",
      "type": "WAIT",
      "wait_duration_hours": 24,
      "next_step_id_on_true": "s3",
      "next_step_id_on_false": null
    },
    {
      "step_id": "s3",
      "type": "ACTION",
      "action_id": "send_notification",
      "params": { "template": "ONBOARDING_SETUP_GUIDE", "channel": "EMAIL" },
      "next_step_id_on_true": "s4",
      "next_step_id_on_false": null
    },
    {
      "step_id": "s4",
      "type": "WAIT",
      "wait_duration_hours": 72,
      "next_step_id_on_true": "s5",
      "next_step_id_on_false": null
    },
    {
      "step_id": "s5",
      "type": "BRANCH",
      "condition": { "field": "pharmacy.is_live", "operator": "eq", "value": true },
      "next_step_id_on_true": "s6",
      "next_step_id_on_false": "s7"
    },
    {
      "step_id": "s6",
      "type": "ACTION",
      "action_id": "send_notification",
      "params": { "template": "CONGRATULATIONS_LIVE", "channel": "WHATSAPP" },
      "next_step_id_on_true": null,
      "next_step_id_on_false": null
    },
    {
      "step_id": "s7",
      "type": "ACTION",
      "action_id": "open_csm_task",
      "params": { "task_title": "Pharmacy not live after 3 days", "priority": "HIGH" },
      "next_step_id_on_true": null,
      "next_step_id_on_false": null
    }
  ]
}
```

**Response 201**
```json
{
  "success": true,
  "data": {
    "id": "uuid-wf-2",
    "name": "PHARMACY_ONBOARDING",
    "status": "INACTIVE",
    "steps_count": 7,
    "version": 1,
    "created_at": "2026-07-24T02:00:00Z"
  },
  "meta": {}
}
```

**Error Table**

| HTTP Code | Error Code | Condition |
|-----------|-----------|-----------|
| 422 | STEP_LIMIT_EXCEEDED | steps_count > 20 |
| 422 | ORPHAN_STEP | A step's next_step_id references a non-existent step_id |
| 422 | CYCLE_DETECTED | Steps form a cycle |
| 409 | WORKFLOW_NAME_EXISTS | name already taken |

---

### GET /api/v1/admin/automation/workflows/:id

Get workflow detail with visual step graph data.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Response 200**
```json
{
  "success": true,
  "data": {
    "id": "uuid-wf-1",
    "name": "DUNNING_LADDER",
    "status": "ACTIVE",
    "version": 1,
    "steps": [
      { "step_id": "s1", "type": "ACTION", "action_id": "send_notification", "params": { "template": "INVOICE_OVERDUE_DAY0" }, "next_step_id_on_true": "s2", "next_step_id_on_false": null },
      { "step_id": "s2", "type": "WAIT", "wait_duration_hours": 72, "next_step_id_on_true": "s3", "next_step_id_on_false": null }
    ],
    "stats": {
      "active_executions": 4,
      "completed_all_time": 142,
      "cancelled_all_time": 12,
      "avg_completion_hours": 168
    }
  },
  "meta": {}
}
```

---

### PATCH /api/v1/admin/automation/workflows/:id

Update workflow (pauses active executions during edit).

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Response 200**
```json
{
  "success": true,
  "data": {
    "id": "uuid-wf-1",
    "version": 2,
    "active_executions_paused": 4,
    "status": "INACTIVE",
    "updated_at": "2026-07-24T09:20:00Z"
  },
  "meta": {}
}
```

---

### POST /api/v1/admin/automation/workflows/:id/toggle

Enable or disable a workflow.

**Auth**: Bearer JWT - `admin_super`

**Request Body**
```json
{ "status": "ACTIVE" }
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "id": "uuid-wf-1",
    "status": "ACTIVE",
    "updated_at": "2026-07-24T09:22:00Z"
  },
  "meta": {}
}
```

---

### GET /api/v1/admin/automation/workflows/:id/executions

Get execution history for a workflow.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Query Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| status | string | No | RUNNING, COMPLETED, FAILED, CANCELLED, PAUSED |
| page | integer | No | Default 1 |

**Response 200**
```json
{
  "success": true,
  "data": {
    "executions": [
      {
        "execution_id": "uuid-exec-1",
        "entity_type": "PHARMACY",
        "entity_id": "uuid-ph-5",
        "entity_name": "Medplus - HSR Layout",
        "started_at": "2026-07-17T10:00:00Z",
        "current_step": "s3",
        "current_step_type": "WAIT",
        "wait_until": "2026-07-24T10:00:00Z",
        "status": "RUNNING",
        "workflow_version": 1
      }
    ]
  },
  "meta": { "page": 1, "limit": 20, "total": 6 }
}
```

---

## Data Models

### automation_workflows

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| name | VARCHAR(200) | Unique |
| description | TEXT | |
| trigger_id | VARCHAR(60) | FK ? trigger_registry |
| steps | JSONB | Array of step definitions |
| status | VARCHAR(15) | ACTIVE, INACTIVE |
| version | INTEGER | Incremented on each PATCH |
| is_seed_workflow | BOOLEAN | |
| created_by | UUID | FK ? admin_users |
| created_at | TIMESTAMPTZ | |
| updated_at | TIMESTAMPTZ | |

### workflow_executions

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| workflow_id | UUID | FK ? automation_workflows |
| workflow_version | INTEGER | Version at execution start |
| entity_type | VARCHAR(30) | PHARMACY, CUSTOMER, etc. |
| entity_id | UUID | |
| current_step_id | VARCHAR(20) | |
| status | VARCHAR(15) | RUNNING, COMPLETED, FAILED, CANCELLED, PAUSED |
| wait_until | TIMESTAMPTZ | Nullable; set during WAIT steps |
| started_at | TIMESTAMPTZ | |
| completed_at | TIMESTAMPTZ | Nullable |
| last_step_executed_at | TIMESTAMPTZ | |
| step_history | JSONB | Array of completed steps with timestamps |

## Acceptance Criteria

1. **AC-001**: POST /workflows with more than 20 steps returns `422 STEP_LIMIT_EXCEEDED`.
2. **AC-002**: A WAIT step persists execution state; a scheduler job resumes the execution after `wait_duration_hours`.
3. **AC-003**: A BRANCH step at execution time evaluates the live condition; true routes to `next_step_id_on_true`, false to `next_step_id_on_false`.
4. **AC-004**: PATCH /workflows/:id for a workflow with 4 active executions sets those executions to PAUSED and returns `active_executions_paused: 4`.
5. **AC-005**: A second trigger fire for an entity already running a workflow execution logs `DUPLICATE_EXECUTION_SKIPPED` and creates no new execution.
6. **AC-006**: GET /workflows/:id/executions returns `wait_until` timestamp for executions currently in a WAIT step.
7. **AC-007**: POST /toggle by an `admin_operations` user returns `403 FORBIDDEN`.
8. **AC-008**: Cancelling an execution stops it at the current step; previously executed steps' actions are not rolled back.

## Dependencies

| Dependency | Type | Notes |
|-----------|------|-------|
| EPIC-019-STORY-001 | Core engine | Condition evaluation, action execution |
| EPIC-019-STORY-002 | Rule patterns | Similar CRUD patterns |
| Scheduler job | Infrastructure | Wakes up WAIT step executions |
| EPIC-019-STORY-005 | Audit | Execution activity logging |

## Notes

- Seed workflows: DUNNING_LADDER, PHARMACY_ONBOARDING, WIN_BACK. These are created during platform initialization via the STORY-008 seed endpoint.
- The step graph visualization in the admin UI uses the `steps` JSONB to render a directed acyclic graph (DAG). The API returns raw step data; rendering is a frontend concern.
