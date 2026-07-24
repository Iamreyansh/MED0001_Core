# STORY-003: SLA and Escalation Management

| Field        | Value                         |
| ------------ | ----------------------------- |
| Story ID     | EPIC-015-STORY-003            |
| Epic         | EPIC-015 Support and Disputes |
| Title        | SLA and Escalation Management |
| Priority     | P1                            |
| Status       | Planned                       |
| Created      | 2026-07-24                    |
| Last Updated | 2026-07-24                    |

---

## Overview

SLA and Escalation Management provides Admin HQ with the configuration layer and real-time monitoring for support response standards. SLA policies define first-response and resolution time targets per ticket category and priority combination. A live breach dashboard surfaces every ticket currently breaching its SLA with elapsed-breach time and breach type (first response vs. resolution). The escalation matrix defines the automated escalation chain triggered on breach - from L1 agents through to senior operations managers at L4. Admins can tune SLA thresholds and escalation rules without a code deployment.

---

## User Roles

| Role               | Capability                                                    |
| ------------------ | ------------------------------------------------------------- |
| `admin_super`      | Full read/write on SLA policies and escalation matrix         |
| `admin_operations` | View SLA policies; view breach list; update escalation matrix |
| `admin_support`    | View SLA breach list for own tickets                          |
| `admin_finance`    | Read-only breach analytics                                    |

---

## Business Rules

1. **SLA timer start** - SLA clock starts at `ticket.created_at`; it is a wall-clock timer, not a business-hours timer (v1 operates 24/7).
2. **SLA timer pause** - timer pauses when ticket status = `AWAITING_CUSTOMER`; resumes when the customer replies (status moves back to IN_PROGRESS).
3. **Breach definition** - a first-response breach occurs when `first_response_at` is null and `now > created_at + first_response_sla_minutes`; a resolution breach occurs when `resolved_at` is null and `now > created_at + resolution_sla_hours - 60`.
4. **Auto-escalation chain** - L1 breach ? auto-escalate to L2 (after 30 min breach); L2 breach ? L3 (after 2 hr breach); L3 breach ? L4 (after 8 hr breach); L4 ? senior ops manager notification.
5. **Escalation via automation engine** - breaches trigger automation engine events; escalation is not performed by the ticket service directly.
6. **Breach list refresh** - the breach dashboard refreshes every 60 seconds (or on-demand via frontend polling); `minutes_breached` is a live computation.
7. **SLA adherence metric** - `sla_adherence_pct = (tickets_within_sla / total_resolved_tickets) - 100` for the selected period; shown in support analytics.
8. **Agent performance link** - breach count is a component of agent performance score; agents with high breach rates are flagged in STORY-004.
9. **Configurable thresholds** - SLA thresholds are stored in the database and read at runtime; no hardcoded values.
10. **Escalation notification channel** - escalation notifications are sent to the escalation team via in-app notification + WhatsApp; channel is configurable per escalation level.

---

## SLA Policy Defaults

| Category | Priority | First Response (min) | Resolution (hours) | SLA Level |
| -------- | -------- | -------------------- | ------------------ | --------- |
| All      | LOW      | 30                   | 24                 | L1        |
| All      | MEDIUM   | 120                  | 48                 | L2        |
| All      | HIGH     | 480                  | 72                 | L3        |
| All      | URGENT   | 1440                 | 96                 | L4        |
| ORDER    | Any      | 30                   | 8                  | L1        |
| PAYMENT  | HIGH     | 120                  | 24                 | L2        |

---

## Escalation Matrix Defaults

| Level | Criteria  | Assigned Team      | Notification Channel     | Auto-Escalate After (min) |
| ----- | --------- | ------------------ | ------------------------ | ------------------------- |
| L1    | Default   | Front-line Agents  | In-App                   | 30                        |
| L2    | L1 breach | Senior Agents      | In-App + WhatsApp        | 120                       |
| L3    | L2 breach | Team Lead          | In-App + WhatsApp        | 480                       |
| L4    | L3 breach | Senior Ops Manager | In-App + WhatsApp + Call | 1440                      |

---

## API Endpoints

### 1. List SLA Policies (Admin)

```
GET /api/v1/admin/support/sla-policies
Authorization: Bearer JWT (admin_super | admin_operations | admin_support | admin_finance)
```

**Response 200**

```json
{
	"success": true,
	"data": {
		"sla_policies": [
			{
				"id": "sla_uuid_001",
				"category": "ORDER",
				"priority": "HIGH",
				"first_response_sla_minutes": 30,
				"resolution_sla_hours": 8,
				"sla_level": "L1",
				"escalation_levels": ["L1", "L2", "L3"]
			},
			{
				"id": "sla_uuid_002",
				"category": "ALL",
				"priority": "MEDIUM",
				"first_response_sla_minutes": 120,
				"resolution_sla_hours": 48,
				"sla_level": "L2",
				"escalation_levels": ["L2", "L3"]
			}
		]
	}
}
```

---

### 2. Update SLA Policy (Admin)

```
PATCH /api/v1/admin/support/sla-policies/:id
Authorization: Bearer JWT (admin_super)
Content-Type: application/json
```

**Request Body**

```json
{
	"first_response_sla_minutes": 45,
	"resolution_sla_minutes": 960
}
```

**Response 200**

```json
{
	"success": true,
	"data": {
		"id": "sla_uuid_001",
		"first_response_sla_minutes": 45,
		"resolution_sla_minutes": 960,
		"updated_at": "2026-07-24T10:00:00Z",
		"updated_by": "admin_uuid_001"
	}
}
```

**Error Responses**

| HTTP | Error Code             | Description                                |
| ---- | ---------------------- | ------------------------------------------ |
| 403  | `FORBIDDEN`            | Only `admin_super` may update SLA policies |
| 404  | `SLA_POLICY_NOT_FOUND` | Policy ID does not exist                   |

---

### 3. Live SLA Breach List (Admin)

```
GET /api/v1/admin/support/sla-breaches
Authorization: Bearer JWT (admin_super | admin_operations | admin_support)
```

**Query Parameters**

| Parameter           | Type   | Description                    |
| ------------------- | ------ | ------------------------------ |
| `breach_type`       | string | `FIRST_RESPONSE`, `RESOLUTION` |
| `sla_level`         | string | `L1`, `L2`, `L3`, `L4`         |
| `assigned_agent_id` | UUID   | Filter by agent                |

**Response 200**

```json
{
	"success": true,
	"data": {
		"breach_count": 6,
		"breaches": [
			{
				"ticket_id": "TKT-20260724-000042",
				"category": "ORDER",
				"customer_name": "Priya Sharma",
				"assigned_agent": "Ravi Kumar",
				"sla_level": "L3",
				"breach_type": "FIRST_RESPONSE",
				"breached_at": "2026-07-24T18:00:00Z",
				"minutes_breached": 125,
				"current_status": "OPEN"
			}
		],
		"last_refreshed_at": "2026-07-24T20:05:00Z"
	}
}
```

---

### 4. Get Escalation Matrix (Admin)

```
GET /api/v1/admin/support/escalation-matrix
Authorization: Bearer JWT (admin_super | admin_operations)
```

**Response 200**

```json
{
	"success": true,
	"data": {
		"escalation_matrix": [
			{
				"level": "L1",
				"criteria": "Default assignment",
				"assigned_team": "Front-line Agents",
				"notification_channel": ["IN_APP"],
				"auto_escalate_after_minutes": 30
			},
			{
				"level": "L2",
				"criteria": "L1 first-response breach",
				"assigned_team": "Senior Agents",
				"notification_channel": ["IN_APP", "WHATSAPP"],
				"auto_escalate_after_minutes": 120
			},
			{
				"level": "L3",
				"criteria": "L2 breach",
				"assigned_team": "Team Lead",
				"notification_channel": ["IN_APP", "WHATSAPP"],
				"auto_escalate_after_minutes": 480
			},
			{
				"level": "L4",
				"criteria": "L3 breach",
				"assigned_team": "Senior Ops Manager",
				"notification_channel": ["IN_APP", "WHATSAPP", "CALL"],
				"auto_escalate_after_minutes": 1440
			}
		]
	}
}
```

---

### 5. Update Escalation Matrix (Admin)

```
PATCH /api/v1/admin/support/escalation-matrix
Authorization: Bearer JWT (admin_super)
Content-Type: application/json
```

**Request Body**

```json
{
	"escalation_rules": [
		{
			"level": "L2",
			"auto_escalate_after_minutes": 90,
			"notification_channel": ["IN_APP", "WHATSAPP", "EMAIL"]
		}
	]
}
```

**Response 200**

```json
{
	"success": true,
	"data": {
		"updated_levels": ["L2"],
		"updated_at": "2026-07-24T10:00:00Z",
		"updated_by": "admin_uuid_001"
	}
}
```

---

## Data Model

### SLAPolicy

| Field                        | Type                   | Constraints      | Description                       |
| ---------------------------- | ---------------------- | ---------------- | --------------------------------- |
| `id`                         | UUID v4                | PK               | Policy identifier                 |
| `category`                   | VARCHAR(20)            | NOT NULL         | Ticket category or `ALL`          |
| `priority`                   | ENUM                   | NOT NULL         | `LOW`, `MEDIUM`, `HIGH`, `URGENT` |
| `first_response_sla_minutes` | INTEGER                | NOT NULL         | First response target             |
| `resolution_sla_minutes`     | INTEGER                | NOT NULL         | Resolution target in minutes      |
| `sla_level`                  | ENUM                   | NOT NULL         | `L1`, `L2`, `L3`, `L4`            |
| `updated_by`                 | UUID                   | FK ? admin_users | Last modifier                     |
| `updated_at`                 | TIMESTAMPTZ            | DEFAULT NOW()    | Last update                       |
| UNIQUE                       | `(category, priority)` |                  | One policy per category+priority  |

### EscalationMatrix

| Field                         | Type         | Constraints      | Description                           |
| ----------------------------- | ------------ | ---------------- | ------------------------------------- |
| `id`                          | UUID v4      | PK               | Rule identifier                       |
| `level`                       | ENUM         | UNIQUE, NOT NULL | `L1`, `L2`, `L3`, `L4`                |
| `criteria`                    | TEXT         | NOT NULL         | Human description                     |
| `assigned_team`               | VARCHAR(100) | NOT NULL         | Team name                             |
| `notification_channel`        | TEXT[]       | NOT NULL         | `IN_APP`, `WHATSAPP`, `EMAIL`, `CALL` |
| `auto_escalate_after_minutes` | INTEGER      | NOT NULL         | Minutes after breach to escalate      |
| `updated_by`                  | UUID         | FK ? admin_users | Last modifier                         |
| `updated_at`                  | TIMESTAMPTZ  | DEFAULT NOW()    | Last update                           |

---

## Acceptance Criteria

1. SLA policy for ORDER/HIGH has `first_response_sla_minutes = 30` and `sla_level = L1` in the default configuration.
2. Admin updates `first_response_sla_minutes` for ORDER/HIGH to 45; change is audit-logged; subsequent tickets use the new value.
3. Breach list shows tickets where first response is overdue; `minutes_breached` increases in real time (verified on two sequential API calls 60 seconds apart).
4. L1 breach auto-triggers escalation to L2 after `auto_escalate_after_minutes = 30` via automation engine.
5. Escalation matrix shows all 4 levels with correct `notification_channel` arrays.
6. Updating escalation matrix L2 `notification_channel` to include EMAIL is persisted and returned in the next GET.
7. SLA timer pauses when ticket enters `AWAITING_CUSTOMER` status; `minutes_breached` does not increase during pause.
8. `sla_adherence_pct` (shown in support analytics) correctly = `(tickets_within_sla / total_resolved) - 100`.
9. Non-`admin_super` role attempting to update SLA policies returns HTTP 403 `FORBIDDEN`.
10. L4 breach triggers notification to Senior Ops Manager via all configured channels (IN_APP + WHATSAPP + CALL).

---

## Dependencies

| Dependency                    | Description                                        |
| ----------------------------- | -------------------------------------------------- |
| Ticket Management (STORY-001) | Ticket status and SLA timestamps                   |
| Automation Engine             | Auto-escalation trigger on breach                  |
| Notification Engine           | Escalation notifications to teams                  |
| Agent Management (STORY-004)  | Breach count fed into agent performance            |
| Scheduled Poller              | Real-time breach detection (runs every 60 seconds) |

---

## Notes

- The breach detection poller runs every 60 seconds as a lightweight query against tickets with `status != RESOLVED` and compares `now` against SLA deadlines.
- SLA policy changes take effect for new tickets only; existing open tickets retain their original SLA deadline unless manually updated.
- In v2, business-hours SLA mode (e.g. 9am-6pm IST) may be introduced; v1 operates on 24/7 calendar time.
