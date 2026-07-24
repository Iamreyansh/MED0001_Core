# STORY-003: SLO & Incident Management

| Field        | Value                                   |
| ------------ | --------------------------------------- |
| Story ID     | EPIC-020-STORY-003                      |
| Epic         | EPIC-020 Observability and Self-Healing |
| Title        | SLO & Incident Management               |
| Priority     | P1                                      |
| Status       | In Development                          |
| Role         | admin_super, admin_operations           |
| Last Updated | 2026-07-24                              |

## Overview

The SLO & Incident Management story provides structured incident lifecycle management and SLO compliance history tracking. Incidents can be declared manually or auto-created from CRITICAL monitoring alerts. Each incident has a severity (P1/P2/P3), affected services, status progression (DETECTED ? INVESTIGATING ? MITIGATING ? RESOLVED), and impacted GMV tracking. P1 and P2 incidents require a postmortem within 48 hours. SLO compliance history is recorded monthly and used for quarterly SLA reporting to stakeholders. Error budget exhaustion freezes non-essential deployments.

## User Roles

| Role             | Access                                          |
| ---------------- | ----------------------------------------------- |
| admin_super      | Full access; declare, update, resolve incidents |
| admin_operations | Full access; declare, update, resolve incidents |
| admin_finance    | Read impacted GMV metrics from incidents        |
| admin_support    | Read incident list; cannot declare or resolve   |

## Business Rules

1. **Auto-Incident Creation**: A CRITICAL severity monitoring alert that is not acknowledged within 15 minutes automatically creates a P1 incident. A HIGH alert not acknowledged within 30 minutes creates a P2 incident.
2. **Severity Definitions**: `P1` = customer-facing outage (payments down, no orders possible); `P2` = significant degradation (SLA > 50% breach rate, dispatch failure > 20%); `P3` = minor issue (single zone issue, low-impact service degradation).
3. **Status Lifecycle**: Incidents move through: `DETECTED ? INVESTIGATING ? MITIGATING ? RESOLVED`. Status can only move forward (no regression). Each transition logs an update_message.
4. **Postmortem Requirement**: P1 and P2 incidents require a postmortem within 48 hours of resolution. The system sends a reminder to admin_super 24 hours after resolution if postmortem not filed. Postmortem fields: root_cause, fix_applied, prevention_steps.
5. **Impacted GMV**: `impacted_gmv_rs` is estimated at incident creation as: (normal hourly GMV rate) - (estimated downtime hours). It is updated on resolution with the actual impact.
6. **SLO Compliance Recording**: At the end of each calendar month, a batch job snapshots SLO compliance for all 4 defined SLOs and writes to `slo_compliance_history`. This data feeds quarterly SLA reports.
7. **Error Budget Freeze**: When any SLO's error_budget_remaining_pct reaches 0%, the system creates a CRITICAL alert `SLO_ERROR_BUDGET_EXHAUSTED`. This is a signal to freeze non-essential feature deployments. Enforcement is a process (not technical gate) in V1.
8. **Incident Notification**: On P1/P2 incident declaration, all admin_super and admin_operations users receive a push notification (HIGH priority) with incident title and severity.
9. **Affected Services List**: Incidents record which services are affected using a predefined list: `ORDER_MANAGEMENT`, `PAYMENT_GATEWAY`, `DISPATCH`, `PHARMACY_ERP`, `RIDER_APP`, `CUSTOMER_APP`, `ANALYTICS`, `AUTOMATION_ENGINE`.
10. **Incident ID Format**: Incident IDs are human-readable: `INC-YYYYMMDD-NNN` (e.g., `INC-20260724-001`). These IDs are used in postmortems and stakeholder communication.

## API Endpoints

### GET /api/v1/admin/monitoring/incidents

List incidents with filter support.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`, `admin_support`

**Query Parameters**

| Parameter | Type    | Required | Description                                   |
| --------- | ------- | -------- | --------------------------------------------- |
| status    | string  | No       | DETECTED, INVESTIGATING, MITIGATING, RESOLVED |
| severity  | string  | No       | P1, P2, P3                                    |
| date_from | string  | No       | ISO 8601                                      |
| date_to   | string  | No       | ISO 8601                                      |
| page      | integer | No       | Default 1                                     |

**Response 200**

```json
{
	"success": true,
	"data": {
		"incidents": [
			{
				"id": "uuid-inc-1",
				"incident_number": "INC-20260724-001",
				"title": "Payment gateway degraded - Razorpay capture success rate below 90%",
				"severity": "P1",
				"status": "MITIGATING",
				"detected_at": "2026-07-24T08:00:00Z",
				"resolved_at": null,
				"duration_minutes": 62,
				"affected_services": ["PAYMENT_GATEWAY", "ORDER_MANAGEMENT"],
				"impacted_gmv_rs": 42000,
				"postmortem_filed": false,
				"created_by": "SYSTEM"
			}
		]
	},
	"meta": { "page": 1, "limit": 20, "total": 4 }
}
```

---

### POST /api/v1/admin/monitoring/incidents

Declare a new incident.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Request Body**

```json
{
	"title": "Payment gateway degraded - Razorpay capture success rate below 90%",
	"severity": "P1",
	"description": "Razorpay payment capture success rate dropped to 88% at 08:00 IST. Orders are failing at checkout. Affects all payment methods.",
	"affected_services": ["PAYMENT_GATEWAY", "ORDER_MANAGEMENT"],
	"impacted_metrics": {
		"payment_success_rate_pct": 88.0,
		"orders_failing_per_minute": 4.2
	}
}
```

**Response 201**

```json
{
	"success": true,
	"data": {
		"id": "uuid-inc-1",
		"incident_number": "INC-20260724-001",
		"title": "Payment gateway degraded - Razorpay capture success rate below 90%",
		"severity": "P1",
		"status": "DETECTED",
		"detected_at": "2026-07-24T09:02:00Z",
		"created_by": "uuid-admin-1"
	},
	"meta": {}
}
```

**Error Table**

| HTTP Code | Error Code       | Condition                                       |
| --------- | ---------------- | ----------------------------------------------- |
| 400       | INVALID_SEVERITY | severity not in P1/P2/P3                        |
| 422       | INVALID_SERVICE  | affected_services contains invalid service name |

---

### PATCH /api/v1/admin/monitoring/incidents/:id

Update incident status during lifecycle.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Request Body**

```json
{
	"status": "MITIGATING",
	"update_message": "Identified root cause: Razorpay webhook processing queue backed up. Restarting webhook consumer service. Payment captures resuming."
}
```

**Response 200**

```json
{
	"success": true,
	"data": {
		"id": "uuid-inc-1",
		"incident_number": "INC-20260724-001",
		"previous_status": "INVESTIGATING",
		"new_status": "MITIGATING",
		"updated_by": "uuid-admin-1",
		"updated_at": "2026-07-24T09:25:00Z"
	},
	"meta": {}
}
```

**Error Table**

| HTTP Code | Error Code                | Condition                          |
| --------- | ------------------------- | ---------------------------------- |
| 422       | INVALID_STATUS_TRANSITION | Attempting to set status backwards |
| 404       | INCIDENT_NOT_FOUND        | id not found                       |

---

### POST /api/v1/admin/monitoring/incidents/:id/resolve

Resolve an incident with root cause and fix details.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Request Body**

```json
{
	"root_cause": "Razorpay webhook consumer service ran out of memory due to a large batch of pending webhooks from a test run. Service auto-restarted but queue remained backed up.",
	"fix_applied": "Manually restarted webhook consumer, cleared stale queue entries, added memory limit to container spec.",
	"prevention_steps": "1. Add memory limit alert for webhook consumer. 2. Add dead letter queue for stuck webhook events. 3. Separate test and production Razorpay webhooks."
}
```

**Response 200**

```json
{
	"success": true,
	"data": {
		"id": "uuid-inc-1",
		"incident_number": "INC-20260724-001",
		"status": "RESOLVED",
		"resolved_at": "2026-07-24T10:05:00Z",
		"duration_minutes": 125,
		"actual_impacted_gmv_rs": 48200,
		"postmortem_required": true,
		"postmortem_deadline": "2026-07-26T10:05:00Z"
	},
	"meta": {}
}
```

**Error Table**

| HTTP Code | Error Code                | Condition                                          |
| --------- | ------------------------- | -------------------------------------------------- |
| 400       | MISSING_REQUIRED_FIELDS   | root_cause, fix_applied, or prevention_steps empty |
| 409       | INCIDENT_ALREADY_RESOLVED | Incident is already RESOLVED                       |

---

### GET /api/v1/admin/monitoring/slo/history

Retrieve SLO compliance history for quarterly reporting.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Query Parameters**

| Parameter   | Type   | Required | Description        |
| ----------- | ------ | -------- | ------------------ |
| slo_name    | string | No       | Filter by SLO name |
| period_from | string | No       | ISO 8601 date      |
| period_to   | string | No       | ISO 8601 date      |

**Response 200**

```json
{
	"success": true,
	"data": {
		"history": [
			{
				"slo_name": "order_sla_adherence",
				"period_from": "2026-07-01",
				"period_to": "2026-07-31",
				"target_pct": 95.0,
				"actual_pct": 93.2,
				"compliant": false,
				"error_budget_consumed_pct": 36.0,
				"incident_count": 2,
				"recorded_at": "2026-08-01T00:05:00Z"
			},
			{
				"slo_name": "payment_success",
				"period_from": "2026-07-01",
				"period_to": "2026-07-31",
				"target_pct": 99.0,
				"actual_pct": 99.4,
				"compliant": true,
				"error_budget_consumed_pct": -40.0,
				"incident_count": 1,
				"recorded_at": "2026-08-01T00:05:00Z"
			}
		]
	},
	"meta": {}
}
```

---

## Data Models

### monitoring_incidents

| Column              | Type          | Notes                                                       |
| ------------------- | ------------- | ----------------------------------------------------------- |
| id                  | UUID          | PK                                                          |
| incident_number     | VARCHAR(20)   | INC-YYYYMMDD-NNN format                                     |
| title               | TEXT          |                                                             |
| severity            | VARCHAR(3)    | P1, P2, P3                                                  |
| description         | TEXT          |                                                             |
| status              | VARCHAR(15)   | DETECTED, INVESTIGATING, MITIGATING, RESOLVED               |
| affected_services   | TEXT[]        | Array of service names                                      |
| impacted_metrics    | JSONB         | Metrics at time of declaration                              |
| impacted_gmv_rs     | DECIMAL(12,2) | Estimated at declaration; actual at resolution              |
| root_cause          | TEXT          | Nullable; filled on resolution                              |
| fix_applied         | TEXT          | Nullable                                                    |
| prevention_steps    | TEXT          | Nullable                                                    |
| postmortem_filed    | BOOLEAN       | Default false                                               |
| postmortem_deadline | TIMESTAMPTZ   | Nullable; 48h after resolved_at for P1/P2                   |
| detected_at         | TIMESTAMPTZ   |                                                             |
| resolved_at         | TIMESTAMPTZ   | Nullable                                                    |
| duration_minutes    | INTEGER       | Computed on resolution                                      |
| created_by          | UUID          | FK ? admin_users (null if SYSTEM)                           |
| status_history      | JSONB         | Array of { status, updated_by, update_message, updated_at } |

### slo_compliance_history

_(defined in STORY-001; extended here with incident_count)_

Additional column: `incident_count INTEGER` - count of P1/P2 incidents in the period that breached this SLO.

## Acceptance Criteria

1. **AC-001**: A CRITICAL monitoring alert not acknowledged within 15 minutes auto-creates a P1 incident with `created_by: SYSTEM`.
2. **AC-002**: POST /incidents returns `incident_number` in `INC-YYYYMMDD-NNN` format.
3. **AC-003**: PATCH /incidents/:id attempting to set `status: DETECTING` on a MITIGATING incident returns `422 INVALID_STATUS_TRANSITION`.
4. **AC-004**: POST /resolve for a P1 incident sets `postmortem_required: true` and `postmortem_deadline` to 48 hours after resolution.
5. **AC-005**: POST /resolve with empty `root_cause` returns `400 MISSING_REQUIRED_FIELDS`.
6. **AC-006**: P1 incident declaration triggers HIGH-priority push notifications to all admin_super and admin_operations users.
7. **AC-007**: GET /slo/history returns `error_budget_consumed_pct` that correctly reflects breach of SLO; negative value indicates SLO compliance with headroom.
8. **AC-008**: `status_history` JSONB array in incident detail contains all status transitions with actor, message, and timestamp.
9. **AC-009**: 24 hours after a P1/P2 incident resolution, if postmortem is not filed, a reminder push notification is sent to admin_super users.

## Dependencies

| Dependency            | Type           | Notes                                   |
| --------------------- | -------------- | --------------------------------------- |
| EPIC-020-STORY-001    | Trigger source | CRITICAL alerts auto-create incidents   |
| EPIC-017-STORY-001    | Transport      | Incident declaration push notifications |
| EPIC-017-STORY-002    | Transport      | On-call SMS paging                      |
| Monthly SLO batch job | Infrastructure | Snapshots SLO compliance at month-end   |
| EPIC-016-STORY-006    | Consumer       | SLO history feeds quarterly reports     |

## Notes

- Incident number generation uses a daily counter reset: INC-20260724-001 is the first incident of July 24, 2026. The counter is stored in Redis for fast atomic increment.
- Postmortem filing is currently in-app (using the PUT /incidents/:id/postmortem endpoint with `postmortem_filed: true`). A formal postmortem template will be added in a future sprint.
- For quarterly SLA reporting, use GET /slo/history with `period_from` = quarter start and `period_to` = quarter end. The data is pre-aggregated monthly so 3 monthly records are returned per SLO per quarter.
