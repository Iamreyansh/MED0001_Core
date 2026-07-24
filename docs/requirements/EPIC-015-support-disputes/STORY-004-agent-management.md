# STORY-004: Agent Management

| Field | Value |
|---|---|
| Story ID | EPIC-015-STORY-004 |
| Epic | EPIC-015 Support and Disputes |
| Title | Agent Management |
| Priority | P1 |
| Status | Planned |
| Created | 2026-07-24 |
| Last Updated | 2026-07-24 |

---

## Overview

Agent Management provides Admin HQ with visibility and control over the support team - their availability (online/offline), workload distribution across ticket categories and priorities, handle time, and CSAT performance. A smart assignment suggestion endpoint helps ops managers route tickets to the best available agent based on specialty, current load, and historical CSAT. The system enforces a 20-ticket workload cap per online agent and surfaces an overflow queue when all agents are at capacity. Weekly performance reports track handle time trends and CSAT scores per agent.

---

## User Roles

| Role | Capability |
|---|---|
| `admin_super` | Full access; toggle any agent's online status |
| `admin_operations` | View all agents; toggle online status; view workload |
| `admin_support` | View own workload and performance; toggle own online status |

---

## Business Rules

1. **Agent role definition** - agents are admin users with role `admin_support`; the Agent Management module reads from `admin_users` filtered by role.
2. **Online/offline** - an agent marked `is_online = false` does not receive auto-assigned tickets; they can still be manually assigned by a supervisor.
3. **Workload cap** - maximum 20 open tickets per online agent; auto-assignment skips agents at capacity.
4. **Auto-assignment algorithm** - selects the online agent with: (a) `is_online = true`, (b) specialty matching the ticket category, (c) lowest `open_load` (count of open tickets), (d) tiebreak by highest `csat_score`.
5. **Specialty matching** - each agent has a list of `specialties` (ticket categories they are trained in); the assignment algorithm prioritises specialty-matched agents.
6. **Overflow queue** - when all online agents are at the 20-ticket cap, unassigned tickets go to the overflow queue; a supervisor notification is triggered.
7. **CSAT per agent** - `agent_csat = MEAN(csat_score from all tickets resolved by the agent in the selected period)`; 1-5 scale.
8. **Handle time** - `avg_handle_minutes = MEAN(resolved_at ? first_response_at)` in minutes across resolved tickets.
9. **Weekly performance report** - generated every Monday at 08:00 IST for the prior week; delivered to the operations manager via email.
10. **Handled today** - `handled_today` = count of tickets resolved by the agent on the current calendar day (IST).

---

## API Endpoints

### 1. List Agents (Admin)

```
GET /api/v1/admin/support/agents
Authorization: Bearer JWT (admin_super | admin_operations)
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "total_agents": 8,
    "online_agents": 5,
    "overflow_queue_count": 0,
    "agents": [
      {
        "id": "admin_uuid_002",
        "name": "Ravi Kumar",
        "role": "admin_support",
        "specialties": ["ORDER", "PAYMENT"],
        "is_online": true,
        "open_load": 12,
        "handled_today": 24,
        "avg_handle_minutes": 18.4,
        "csat_score": 4.6
      },
      {
        "id": "admin_uuid_003",
        "name": "Sneha Rao",
        "role": "admin_support",
        "specialties": ["PHARMACY", "RIDER"],
        "is_online": false,
        "open_load": 0,
        "handled_today": 8,
        "avg_handle_minutes": 22.1,
        "csat_score": 4.2
      }
    ]
  }
}
```

---

### 2. Toggle Agent Online Status (Admin)

```
PATCH /api/v1/admin/support/agents/:id/status
Authorization: Bearer JWT (admin_super | admin_operations | admin_support)
Content-Type: application/json
```

**Request Body**
```json
{ "is_online": true }
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "agent_id": "admin_uuid_002",
    "is_online": true,
    "toggled_at": "2026-07-24T09:00:00Z"
  }
}
```

**Error Responses**

| HTTP | Error Code | Description |
|---|---|---|
| 403 | `FORBIDDEN` | `admin_support` can only toggle own status |
| 404 | `AGENT_NOT_FOUND` | Agent ID does not exist |

---

### 3. Get Agent Detail (Admin)

```
GET /api/v1/admin/support/agents/:id
Authorization: Bearer JWT (admin_super | admin_operations)
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "id": "admin_uuid_002",
    "name": "Ravi Kumar",
    "email": "ravi.kumar@nammamedmate.com",
    "specialties": ["ORDER", "PAYMENT"],
    "is_online": true,
    "open_load": 12,
    "handled_today": 24,
    "avg_handle_minutes": 18.4,
    "csat_score": 4.6,
    "sla_breach_count_this_week": 2,
    "at_risk_tickets": [
      {
        "ticket_id": "TKT-20260724-000042",
        "category": "ORDER",
        "sla_due_at": "2026-07-24T18:00:00Z",
        "minutes_to_breach": 35
      }
    ],
    "performance_trend": [
      { "week": "2026-W29", "csat": 4.5, "handled": 112, "avg_handle_min": 19.2 },
      { "week": "2026-W30", "csat": 4.6, "handled": 130, "avg_handle_min": 18.4 }
    ]
  }
}
```

---

### 4. Get Agent Workload (Admin)

```
GET /api/v1/admin/support/agents/:id/workload
Authorization: Bearer JWT (admin_super | admin_operations | admin_support)
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "agent_id": "admin_uuid_002",
    "open_tickets_breakdown": [
      { "category": "ORDER", "priority": "HIGH", "count": 6 },
      { "category": "ORDER", "priority": "MEDIUM", "count": 4 },
      { "category": "PAYMENT", "priority": "HIGH", "count": 2 }
    ],
    "recent_resolved": [
      {
        "ticket_id": "TKT-20260724-000038",
        "category": "ORDER",
        "handle_minutes": 14,
        "csat_score": 5
      }
    ],
    "handle_time_distribution": [
      { "bucket": "0-15 min", "count": 42 },
      { "bucket": "15-30 min", "count": 68 },
      { "bucket": "30-60 min", "count": 18 },
      { "bucket": "60+ min", "count": 5 }
    ]
  }
}
```

---

### 5. Suggest Agent Assignment (Admin)

```
GET /api/v1/admin/support/agents/suggest-assignment
Authorization: Bearer JWT (admin_super | admin_operations | admin_support)
```

**Query Parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `ticket_id` | UUID | Yes | Ticket to assign |

**Response 200**
```json
{
  "success": true,
  "data": {
    "ticket_id": "tkt_uuid_001",
    "suggested_agent": {
      "id": "admin_uuid_002",
      "name": "Ravi Kumar",
      "is_online": true,
      "open_load": 12,
      "csat_score": 4.6,
      "specialty_match": true
    },
    "alternative_agents": [
      {
        "id": "admin_uuid_004",
        "name": "Karthik Menon",
        "is_online": true,
        "open_load": 14,
        "csat_score": 4.3,
        "specialty_match": true
      }
    ],
    "overflow": false
  }
}
```

**Error Responses**

| HTTP | Error Code | Description |
|---|---|---|
| 404 | `TICKET_NOT_FOUND` | Ticket ID does not exist |
| 200 | `overflow: true` | All agents at capacity (not an error HTTP-wise) |

---

## Data Model

### AgentProfile (extends AdminUser)

| Field | Type | Constraints | Description |
|---|---|---|---|
| `admin_user_id` | UUID | FK ? admin_users, PK | Links to admin user |
| `specialties` | VARCHAR(20)[] | DEFAULT {} | Ticket categories specialised in |
| `is_online` | BOOLEAN | DEFAULT false | Active availability status |
| `max_load` | INTEGER | DEFAULT 20 | Ticket capacity |
| `updated_at` | TIMESTAMPTZ | DEFAULT NOW() | Last profile update |

### AgentPerformanceSnapshot (weekly)

| Field | Type | Constraints | Description |
|---|---|---|---|
| `id` | UUID v4 | PK | Snapshot record |
| `agent_id` | UUID | FK ? admin_users | Agent |
| `week_start` | DATE | NOT NULL | ISO week start (Monday) |
| `tickets_handled` | INTEGER | DEFAULT 0 | Resolved in week |
| `avg_handle_minutes` | DECIMAL(6,2) | NULLABLE | Average handle time |
| `csat_score_avg` | DECIMAL(3,2) | NULLABLE | CSAT average (1.00-5.00) |
| `sla_breach_count` | INTEGER | DEFAULT 0 | Breaches in week |
| UNIQUE | `(agent_id, week_start)` | | One snapshot per agent per week |

---

## Acceptance Criteria

1. Agent roster shows all `admin_support` role users with correct `open_load`, `is_online`, and `csat_score`.
2. An agent with `open_load = 20` is not suggested by the assignment algorithm and is skipped in auto-assignment.
3. Agent going online (`is_online = true`) immediately becomes eligible for auto-assignment.
4. `admin_support` user can toggle only their own online status; attempting to toggle another agent's status returns HTTP 403.
5. Suggest-assignment returns an agent with `specialty_match = true` when a matching online agent is available.
6. When all agents are at capacity, suggest-assignment returns `overflow: true` and no `suggested_agent`.
7. Agent detail shows correct `sla_breach_count_this_week` matching the breach list for that agent.
8. Workload breakdown correctly sums to `open_load` across all categories and priorities.
9. `avg_handle_minutes` = mean of `(resolved_at ? first_response_at)` in minutes across resolved tickets in the period.
10. Weekly performance report is generated and emailed every Monday at 08:00 IST (verifiable via scheduled job log).

---

## Dependencies

| Dependency | Description |
|---|---|
| Admin User Management | Agent records from `admin_users` table |
| Ticket Management (STORY-001) | Open load, handle time, CSAT scores |
| SLA Management (STORY-003) | SLA breach counts for agent performance |
| Notification Engine | Weekly report email delivery; overflow alerts |
| Scheduled Job Runner | Weekly performance snapshot generation |

---

## Notes

- `handled_today` is reset at midnight IST (not UTC) via a scheduled job.
- CSAT score per agent is computed as a rolling 30-day average by default in the roster view; filterable by period.
- "At-risk tickets" in agent detail shows tickets assigned to the agent with `sla_due_at` within the next 60 minutes.
