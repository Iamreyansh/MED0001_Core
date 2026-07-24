# STORY-006: Approvals Queue

| Field | Value |
|-------|-------|
| Story ID | EPIC-019-STORY-006 |
| Epic | EPIC-019 Automation and Rules Engine |
| Title | Approvals Queue |
| Priority | P1 |
| Status | In Development |
| Role | admin_super, admin_operations |
| Last Updated | 2026-07-24 |

## Overview

The Approvals Queue story implements the human-in-the-loop safety layer for the automation engine. When an automated action's value exceeds a guardrail cap, the action type is in the ALWAYS_REQUIRE_APPROVAL list, or the rule has `require_approval: true`, the action is paused and placed into the approvals queue for human review. Admins can approve (which immediately executes the action) or reject (which cancels the action and optionally triggers an alternative). Pending approvals expire after 4 hours (configurable), with expired items routed to fallback actions.

## User Roles

| Role | Access |
|------|--------|
| admin_super | Approve and reject any pending approval |
| admin_operations | Approve and reject any pending approval |
| admin_finance | Approve/reject FINANCE category approvals only |

## Business Rules

1. **Routing to Queue**: Actions enter the approvals queue when: (a) action amount > `guardrails.value_cap`, (b) action_type in ALWAYS_REQUIRE_APPROVAL list (`mass_suspension`, `payout_above_1_lakh`), or (c) rule has `require_approval: true`.
2. **ALWAYS_REQUIRE_APPROVAL List**: The following actions always require approval regardless of guardrails: `suspend_entity` (mass suspension of > 5 entities), individual payout above Rs 1,00,000.
3. **Expiry**: Pending approvals expire after 4 hours. On expiry, status changes to `EXPIRED`, the action is NOT executed, and the alternative action (if configured in rule) fires. Admin is notified of expiry.
4. **Urgency Classification**: `urgency: URGENT` when the pending action involves an SLA breach or transaction > Rs 50,000. `urgency: NORMAL` otherwise.
5. **Approval Notification**: When a new approval enters the queue, all eligible approvers (admin_super and admin_operations) receive a push notification (HIGH priority) with a deep-link to the approval detail screen.
6. **Rejection Alternative Action**: If the rule's guardrail has `on_reject_action` configured (e.g., `open_csm_task` instead of `release_payout`), rejection triggers this alternative action.
7. **Queue Deduplication**: If an identical approval (same rule + same entity + same action type) is already PENDING in the queue, a new trigger for the same entity is not added (duplicate prevention). The existing pending item is re-notified to approvers.
8. **Approval Audit**: Every approve/reject action is logged in `automation_activity_log` with `actor: HUMAN`, `override_by: approver_user_id`, and a reference to the original pending action.
9. **Stats Availability**: `avg_response_time_minutes` is computed over the last 7 days of approved/rejected items. `approval_rate_pct = approved / (approved + rejected) - 100` for the same period.
10. **Financial Approvals Role Scope**: For FINANCE category approvals (payout releases), `admin_finance` can approve. For non-financial (suspension, plan change), only `admin_super` or `admin_operations`.

## API Endpoints

### GET /api/v1/admin/automation/approvals

List pending and recent approvals in the queue.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`, `admin_finance`

**Query Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| status | string | No | PENDING, APPROVED, REJECTED, EXPIRED (default: PENDING) |
| urgency | string | No | URGENT, NORMAL |
| page | integer | No | Default 1 |
| limit | integer | No | Default 20 |

**Response 200**
```json
{
  "success": true,
  "data": {
    "chips": {
      "pending_count": 3,
      "urgent_count": 1,
      "approved_today": 8,
      "rejected_today": 1
    },
    "approvals": [
      {
        "approval_id": "uuid-appr-1",
        "rule_name": "Auto-release due payouts",
        "action_type": "release_payout",
        "entity_type": "PHARMACY",
        "entity_id": "uuid-ph-1",
        "entity_name": "Apollo Pharmacy - Indiranagar",
        "amount_rs": 48000,
        "urgency": "URGENT",
        "triggered_at": "2026-07-24T07:00:00Z",
        "expires_at": "2026-07-24T11:00:00Z",
        "status": "PENDING"
      },
      {
        "approval_id": "uuid-appr-2",
        "rule_name": "Auto-suspend high-risk riders",
        "action_type": "suspend_entity",
        "entity_type": "RIDER",
        "entity_id": "uuid-rider-12",
        "entity_name": "Suresh Babu",
        "amount_rs": null,
        "urgency": "NORMAL",
        "triggered_at": "2026-07-24T08:30:00Z",
        "expires_at": "2026-07-24T12:30:00Z",
        "status": "PENDING"
      }
    ]
  },
  "meta": { "page": 1, "limit": 20, "total": 3 }
}
```

---

### GET /api/v1/admin/automation/approvals/:id

Get approval detail with trigger context and proposed action impact.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`, `admin_finance`

**Response 200**
```json
{
  "success": true,
  "data": {
    "approval_id": "uuid-appr-1",
    "rule_name": "Auto-release due payouts",
    "trigger_context": {
      "trigger_event": "payout_cycle_reached",
      "entity_type": "PHARMACY",
      "entity_id": "uuid-ph-1",
      "entity_name": "Apollo Pharmacy - Indiranagar",
      "payload": {
        "payout_amount_paise": 4800000,
        "payout_period": "2026-07-01 to 2026-07-15",
        "orders_count": 412
      }
    },
    "conditions_met": [
      { "field": "payout.amount", "operator": "amount_gt", "value": 5000000, "resolved_value": 4800000, "result": false }
    ],
    "proposed_action": {
      "action_type": "release_payout",
      "params": {
        "entity_type": "PHARMACY",
        "entity_id": "uuid-ph-1",
        "amount_paise": 4800000,
        "mode": "IMPS"
      }
    },
    "estimated_impact": "Release Rs 48,000 to Apollo Pharmacy - Indiranagar bank account ending ****4821.",
    "why_requires_approval": "Payout amount Rs 48,000 exceeds auto-approval limit (Rs 50,000 cap is configured, but this rule routes all payouts above Rs 40,000 to approval queue as a conservative guardrail).",
    "urgency": "URGENT",
    "triggered_at": "2026-07-24T07:00:00Z",
    "expires_at": "2026-07-24T11:00:00Z",
    "status": "PENDING"
  },
  "meta": {}
}
```

---

### POST /api/v1/admin/automation/approvals/:id/approve

Approve and immediately execute the proposed action.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`, `admin_finance`

**Request Body**
```json
{
  "notes": "Verified payout amount matches settlement register. Approved."
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "approval_id": "uuid-appr-1",
    "status": "APPROVED",
    "action_executed": true,
    "activity_log_id": "uuid-act-3",
    "approved_by": "uuid-admin-1",
    "approved_at": "2026-07-24T09:45:00Z"
  },
  "meta": {}
}
```

**Error Table**

| HTTP Code | Error Code | Condition |
|-----------|-----------|-----------|
| 404 | APPROVAL_NOT_FOUND | ID not found |
| 409 | APPROVAL_ALREADY_RESOLVED | Approval is not in PENDING status |
| 410 | APPROVAL_EXPIRED | Approval has expired |
| 403 | FORBIDDEN | Role cannot approve this action type |

---

### POST /api/v1/admin/automation/approvals/:id/reject

Reject the approval (action is not executed; alternative fires if configured).

**Auth**: Bearer JWT - `admin_super`, `admin_operations`, `admin_finance`

**Request Body**
```json
{
  "reason": "Payout disputed - pharmacy issued incorrect invoices for this period. Holding pending investigation."
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "approval_id": "uuid-appr-1",
    "status": "REJECTED",
    "alternative_action_fired": false,
    "rejected_by": "uuid-admin-1",
    "rejected_at": "2026-07-24T09:46:00Z"
  },
  "meta": {}
}
```

**Error Table**

| HTTP Code | Error Code | Condition |
|-----------|-----------|-----------|
| 400 | REASON_REQUIRED | reason is empty or null |
| 409 | APPROVAL_ALREADY_RESOLVED | Approval is not PENDING |

---

### GET /api/v1/admin/automation/approvals/stats

Approval queue performance statistics.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Response 200**
```json
{
  "success": true,
  "data": {
    "avg_response_time_minutes": 18.4,
    "approval_rate_pct": 88.9,
    "rejection_rate_pct": 11.1,
    "expiry_rate_pct": 4.2,
    "top_pending_categories": [
      { "category": "FINANCE", "count": 2 },
      { "category": "ADMIN", "count": 1 }
    ],
    "period_days": 7
  },
  "meta": {}
}
```

---

## Data Models

### automation_approvals

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| rule_id | UUID | FK ? automation_rules |
| trigger_event_id | UUID | FK ? trigger_events |
| action_type | VARCHAR(60) | |
| action_params | JSONB | Proposed action parameters |
| entity_type | VARCHAR(30) | |
| entity_id | UUID | |
| amount_paise | BIGINT | Nullable; for financial actions |
| category | VARCHAR(20) | FINANCE, ADMIN, CRM |
| urgency | VARCHAR(10) | URGENT, NORMAL |
| why_requires_approval | TEXT | Explanation string |
| status | VARCHAR(15) | PENDING, APPROVED, REJECTED, EXPIRED |
| approved_by | UUID | FK ? admin_users (nullable) |
| rejected_by | UUID | FK ? admin_users (nullable) |
| approval_notes | TEXT | Nullable |
| rejection_reason | TEXT | Nullable |
| activity_log_id | UUID | FK ? automation_activity_log (post-approval) |
| triggered_at | TIMESTAMPTZ | |
| expires_at | TIMESTAMPTZ | triggered_at + 4 hours (configurable) |
| resolved_at | TIMESTAMPTZ | Nullable |

## Acceptance Criteria

1. **AC-001**: GET /approvals returns `chips.pending_count` matching the exact count of PENDING approvals.
2. **AC-002**: POST /approve immediately executes the action and creates an activity log entry with `actor: HUMAN, override_by: approver_id`.
3. **AC-003**: POST /approve for an EXPIRED approval returns `410 APPROVAL_EXPIRED`.
4. **AC-004**: POST /reject requires a non-empty `reason`; empty reason returns `400 REASON_REQUIRED`.
5. **AC-005**: An `admin_finance` user successfully approves a FINANCE category payout approval.
6. **AC-006**: An `admin_finance` user attempting to approve an ADMIN category (suspension) approval returns `403 FORBIDDEN`.
7. **AC-007**: Approval expiry (after 4 hours) automatically sets `status: EXPIRED` and fires the alternative action if configured.
8. **AC-008**: All eligible approvers receive a HIGH-priority push notification when a new approval enters the queue.
9. **AC-009**: GET /approvals/stats returns `avg_response_time_minutes` computed from the last 7 days of resolved approvals.

## Dependencies

| Dependency | Type | Notes |
|-----------|------|-------|
| EPIC-019-STORY-001 | Writer | Routes actions to approvals queue |
| EPIC-019-STORY-005 | Writer | Activity log on approve/reject |
| EPIC-017-STORY-001 | Transport | Push notifications to approvers |
| Expiry scheduler job | Infrastructure | Auto-expire PENDING approvals after 4 hours |

## Notes

- The 4-hour expiry window is configurable in admin settings per action type. High-urgency financial actions might have a 1-hour expiry.
- The `chips` summary in the GET /approvals response is computed in real-time (no caching) to ensure accuracy of the urgent count.
- Future: Approval delegation - allow admin_super to delegate approval authority for a time period.
