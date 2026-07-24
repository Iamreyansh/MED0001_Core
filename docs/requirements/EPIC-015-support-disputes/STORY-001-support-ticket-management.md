# STORY-001: Support Ticket Management

| Field | Value |
|---|---|
| Story ID | EPIC-015-STORY-001 |
| Epic | EPIC-015 Support and Disputes |
| Title | Support Ticket Management |
| Priority | P0 |
| Status | Planned |
| Created | 2026-07-24 |
| Last Updated | 2026-07-24 |

---

## Overview

Support Ticket Management is the core CX infrastructure for Namma MedMate, handling every inbound support request from customers, pharmacies, and admins across channels (App, Email, Phone, WhatsApp). Tickets go through a structured lifecycle (OPEN ? IN_PROGRESS ? RESOLVED ? CLOSED) with SLA timers enforced at four levels (L1-L4). Agents are assigned via round-robin, can reply using canned responses, leave internal notes, escalate tickets, and change priority. Customers can reopen resolved tickets by replying, creating a natural conversation thread. CSAT surveys are sent 24 hours post-resolution to measure satisfaction.

---

## User Roles

| Role | Capability |
|---|---|
| `admin_super` | Full access; override any ticket; reassign agents |
| `admin_operations` | View all tickets; assign, escalate, resolve |
| `admin_support` | Handle assigned tickets; reply; resolve; escalate |
| `customer` | Create tickets; view own tickets; reply to own tickets |
| `pharmacy_owner` | Create pharmacy-side tickets; view own tickets |
| `pharmacy_staff` | Create tickets for pharmacy issues |

---

## Business Rules

1. **SLA levels and response times** - L1: first response within 30 minutes; L2: 2 hours; L3: 8 hours; L4: 24 hours. SLA level is derived from ticket priority (LOW=L1, MEDIUM=L2, HIGH=L3, URGENT=L4) and/or category.
2. **SLA timer behaviour** - SLA timer starts at ticket creation, pauses when awaiting customer reply (`status = AWAITING_CUSTOMER`), and resumes when the customer replies.
3. **Auto-escalation on breach** - if first response SLA is breached, the ticket is auto-escalated to the next level via the automation engine.
4. **Customer reply auto-reopens** - when a customer sends a message to a RESOLVED ticket, the ticket status automatically changes to IN_PROGRESS and SLA clock restarts.
5. **Round-robin auto-assignment** - tickets are auto-assigned to the online agent with the lowest current open ticket count, matching the ticket's category to the agent's specialties.
6. **Agent workload cap** - an agent cannot be auto-assigned more than 20 open tickets; if all agents are at capacity, ticket goes to the overflow queue (unassigned).
7. **CSAT survey delivery** - sent 24 hours after ticket resolution via the same channel the ticket was created on (in-app, email, or WhatsApp).
8. **Internal notes** - replies with `is_internal_note = true` are visible only to admin/support users, not to the customer.
9. **Ticket ID format** - `TKT-YYYYMMDD-XXXXXX` (zero-padded 6-digit sequential per day).
10. **Admin creation on behalf** - admin can create a ticket on behalf of a customer; the ticket is attributed to the customer (`customer_id`), not the admin.

---

## API Endpoints

### 1. List Tickets (Admin Queue)

```
GET /api/v1/admin/support/tickets
Authorization: Bearer JWT (admin_super | admin_operations | admin_support)
```

**Query Parameters**

| Parameter | Type | Description |
|---|---|---|
| `status` | string | `OPEN`, `IN_PROGRESS`, `RESOLVED`, `CLOSED` |
| `priority` | string | `LOW`, `MEDIUM`, `HIGH`, `URGENT` |
| `category` | string | `ORDER`, `PAYMENT`, `PHARMACY`, `RIDER`, `ACCOUNT`, `PRODUCT`, `OTHER` |
| `channel` | string | `APP`, `EMAIL`, `PHONE`, `WHATSAPP` |
| `q` | string | Search ticket_id or customer name |
| `assigned_agent_id` | UUID | Filter by agent |
| `page` | integer | Default 1 |
| `limit` | integer | Default 20 |
| `export` | boolean | `true` to download CSV |

**Response 200**
```json
{
  "success": true,
  "data": {
    "chips": {
      "open": 84,
      "in_progress": 32,
      "sla_breached": 6,
      "open_disputes": 14,
      "refund_exposure_rs": 42800,
      "csat_pct": 87.4
    },
    "tickets": [
      {
        "id": "tkt_uuid_001",
        "ticket_id": "TKT-20260724-000042",
        "customer_name": "Priya Sharma",
        "category": "ORDER",
        "subject": "Wrong items delivered",
        "status": "OPEN",
        "priority": "HIGH",
        "sla_level": "L3",
        "sla_due_at": "2026-07-24T18:00:00Z",
        "sla_breached": false,
        "channel": "APP",
        "assigned_agent_id": null,
        "created_at": "2026-07-24T10:00:00Z"
      }
    ]
  },
  "meta": { "page": 1, "limit": 20, "total": 116 }
}
```

---

### 2. Create Ticket (Customer / Pharmacy / Admin)

```
POST /api/v1/support/tickets
Authorization: Bearer JWT (customer | pharmacy_owner | pharmacy_staff | admin_support | admin_operations | admin_super)
Content-Type: application/json
```

**Request Body**
```json
{
  "category": "ORDER",
  "subject": "Wrong items delivered in my order",
  "description": "I ordered paracetamol 500mg but received ibuprofen 400mg instead.",
  "channel": "APP",
  "order_id": "ord_uuid_001",
  "attachments": ["https://cdn.nammamedmate.com/attachments/img_001.jpg"]
}
```

**Response 201**
```json
{
  "success": true,
  "data": {
    "id": "tkt_uuid_001",
    "ticket_id": "TKT-20260724-000042",
    "status": "OPEN",
    "priority": "HIGH",
    "sla_level": "L3",
    "sla_due_at": "2026-07-24T18:00:00Z",
    "created_at": "2026-07-24T10:00:00Z"
  }
}
```

---

### 3. Get Ticket Detail (Admin)

```
GET /api/v1/support/tickets/:id
Authorization: Bearer JWT (admin_super | admin_operations | admin_support | customer | pharmacy_owner)
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "id": "tkt_uuid_001",
    "ticket_id": "TKT-20260724-000042",
    "subject": "Wrong items delivered",
    "status": "IN_PROGRESS",
    "priority": "HIGH",
    "sla_level": "L3",
    "sla_due_at": "2026-07-24T18:00:00Z",
    "sla_breached": false,
    "customer_context": {
      "customer_id": "cust_uuid_001",
      "customer_name": "Priya Sharma",
      "total_orders": 24,
      "ltv_rs": 8400
    },
    "order_id": "ord_uuid_001",
    "conversation": [
      {
        "message_id": "msg_uuid_001",
        "sender": "customer",
        "sender_name": "Priya Sharma",
        "message": "I ordered paracetamol 500mg but received ibuprofen 400mg.",
        "is_internal_note": false,
        "attachments": [],
        "created_at": "2026-07-24T10:00:00Z"
      },
      {
        "message_id": "msg_uuid_002",
        "sender": "agent",
        "sender_name": "Ravi Kumar",
        "message": "Hi Priya, I'm sorry to hear that. I'm looking into this right away.",
        "is_internal_note": false,
        "canned_response_id": null,
        "created_at": "2026-07-24T10:12:00Z"
      }
    ],
    "assigned_agent_id": "admin_uuid_002",
    "assigned_agent_name": "Ravi Kumar",
    "first_response_at": "2026-07-24T10:12:00Z",
    "created_at": "2026-07-24T10:00:00Z"
  }
}
```

---

### 4. Reply to Ticket

```
POST /api/v1/support/tickets/:id/reply
Authorization: Bearer JWT (admin_support | admin_operations | admin_super | customer | pharmacy_owner)
Content-Type: application/json
```

**Request Body**
```json
{
  "message": "I have initiated a replacement for your order. It will arrive within 2 hours.",
  "is_internal_note": false,
  "attachments": [],
  "canned_response_id": "cr_uuid_001"
}
```

**Response 201**
```json
{
  "success": true,
  "data": {
    "message_id": "msg_uuid_003",
    "ticket_id": "TKT-20260724-000042",
    "sender": "agent",
    "created_at": "2026-07-24T10:20:00Z"
  }
}
```

---

### 5. Assign Ticket to Agent

```
POST /api/v1/support/tickets/:id/assign
Authorization: Bearer JWT (admin_super | admin_operations | admin_support)
Content-Type: application/json
```

**Request Body**
```json
{ "agent_id": "admin_uuid_002" }
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "ticket_id": "TKT-20260724-000042",
    "assigned_to": "admin_uuid_002",
    "assigned_to_name": "Ravi Kumar",
    "assigned_at": "2026-07-24T10:05:00Z"
  }
}
```

**Error Responses**

| HTTP | Error Code | Description |
|---|---|---|
| 400 | `AGENT_AT_CAPACITY` | Agent has 20 open tickets |
| 404 | `AGENT_NOT_FOUND` | Agent ID does not exist |

---

### 6. Resolve Ticket

```
POST /api/v1/support/tickets/:id/resolve
Authorization: Bearer JWT (admin_super | admin_operations | admin_support)
Content-Type: application/json
```

**Request Body**
```json
{
  "resolution_summary": "Replacement order dispatched. Wrongly delivered items collected."
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "ticket_id": "TKT-20260724-000042",
    "status": "RESOLVED",
    "resolved_at": "2026-07-24T11:00:00Z",
    "csat_survey_scheduled_at": "2026-07-25T11:00:00Z"
  }
}
```

---

### 7. Reopen Ticket

```
POST /api/v1/support/tickets/:id/reopen
Authorization: Bearer JWT (admin_super | admin_operations | admin_support | customer)
Content-Type: application/json
```

**Request Body**
```json
{ "reason": "Issue not fully resolved - replacement order is also wrong." }
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "ticket_id": "TKT-20260724-000042",
    "status": "IN_PROGRESS",
    "reopened_at": "2026-07-24T14:00:00Z"
  }
}
```

---

### 8. Escalate Ticket

```
POST /api/v1/support/tickets/:id/escalate
Authorization: Bearer JWT (admin_super | admin_operations | admin_support)
Content-Type: application/json
```

**Request Body**
```json
{
  "escalation_level": "L3",
  "reason": "Customer has been waiting 4 hours with no resolution."
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "ticket_id": "TKT-20260724-000042",
    "sla_level": "L3",
    "escalated_at": "2026-07-24T14:00:00Z",
    "supervisor_notified": true
  }
}
```

---

### 9. Change Ticket Priority

```
PATCH /api/v1/support/tickets/:id/priority
Authorization: Bearer JWT (admin_super | admin_operations | admin_support)
Content-Type: application/json
```

**Request Body**
```json
{ "priority": "URGENT" }
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "ticket_id": "TKT-20260724-000042",
    "priority": "URGENT",
    "sla_level": "L4",
    "updated_at": "2026-07-24T10:05:00Z"
  }
}
```

---

### 10. Admin Create Ticket on Behalf of Customer

```
POST /api/v1/admin/support/tickets
Authorization: Bearer JWT (admin_super | admin_operations | admin_support)
Content-Type: application/json
```

**Request Body**
```json
{
  "customer_id": "cust_uuid_001",
  "category": "PAYMENT",
  "subject": "Refund not received after 7 days",
  "description": "Customer called in. Refund for order ORD-20260717-001234 not received.",
  "order_id": "ord_uuid_001"
}
```

**Response 201**
```json
{
  "success": true,
  "data": {
    "ticket_id": "TKT-20260724-000043",
    "created_for_customer_id": "cust_uuid_001",
    "created_by_admin_id": "admin_uuid_001",
    "status": "OPEN"
  }
}
```

---

## Data Model

### Ticket

| Field | Type | Constraints | Description |
|---|---|---|---|
| `id` | UUID v4 | PK | Internal identifier |
| `ticket_id` | VARCHAR(22) | UNIQUE, NOT NULL | Human-readable ID |
| `customer_id` | UUID | FK ? customers | Ticket owner |
| `pharmacy_id` | UUID | NULLABLE FK ? pharmacies | Pharmacy context |
| `order_id` | UUID | NULLABLE FK ? orders | Related order |
| `category` | ENUM | NOT NULL | `ORDER`, `PAYMENT`, `PHARMACY`, `RIDER`, `ACCOUNT`, `PRODUCT`, `OTHER` |
| `subject` | VARCHAR(200) | NOT NULL | Subject line |
| `status` | ENUM | DEFAULT OPEN | `OPEN`, `IN_PROGRESS`, `RESOLVED`, `CLOSED` |
| `priority` | ENUM | DEFAULT MEDIUM | `LOW`, `MEDIUM`, `HIGH`, `URGENT` |
| `sla_level` | ENUM | NOT NULL | `L1`, `L2`, `L3`, `L4` |
| `assigned_agent_id` | UUID | NULLABLE FK ? admin_users | Assigned agent |
| `channel` | ENUM | NOT NULL | `APP`, `EMAIL`, `PHONE`, `WHATSAPP` |
| `first_response_at` | TIMESTAMPTZ | NULLABLE | First agent response time |
| `resolved_at` | TIMESTAMPTZ | NULLABLE | Resolution time |
| `csat_score` | INTEGER | NULLABLE | 1-5 CSAT rating |
| `csat_feedback` | TEXT | NULLABLE | CSAT comment |
| `created_at` | TIMESTAMPTZ | DEFAULT NOW() | Creation time |
| `updated_at` | TIMESTAMPTZ | DEFAULT NOW() | Last update |

### TicketMessage

| Field | Type | Constraints | Description |
|---|---|---|---|
| `id` | UUID v4 | PK | Message ID |
| `ticket_id` | UUID | FK ? tickets | Parent ticket |
| `sender_type` | ENUM | NOT NULL | `CUSTOMER`, `AGENT`, `SYSTEM` |
| `sender_id` | UUID | NOT NULL | Customer or agent UUID |
| `sender_name` | VARCHAR(100) | NOT NULL | Denormalised name |
| `message` | TEXT | NOT NULL | Message body |
| `is_internal_note` | BOOLEAN | DEFAULT false | Visibility flag |
| `canned_response_id` | UUID | NULLABLE | Canned response used |
| `attachments` | TEXT[] | DEFAULT {} | Attachment URLs |
| `created_at` | TIMESTAMPTZ | DEFAULT NOW() | Message timestamp |

---

## Acceptance Criteria

1. Customer creates a ticket; ticket appears in admin queue with correct `ticket_id` format `TKT-YYYYMMDD-XXXXXX`.
2. HIGH priority ticket has `sla_level = L3` and `sla_due_at` = creation time + 8 hours.
3. SLA breach after 8 hours for L3 triggers auto-escalation to L4 via automation engine.
4. Agent replies with `is_internal_note = true`; internal note is not visible to customer in ticket thread.
5. Customer replies to a RESOLVED ticket; status auto-changes to IN_PROGRESS.
6. Round-robin assignment respects the 20-ticket cap: agent at 20 open tickets is not assigned new tickets.
7. CSAT survey is scheduled 24 hours after `resolved_at` timestamp.
8. Admin creates ticket on behalf of customer; `customer_id` is the customer's (not the admin's).
9. Priority change to URGENT updates `sla_level` to L4 immediately.
10. CSV export of ticket queue returns all visible columns for the filtered set.

---

## Dependencies

| Dependency | Description |
|---|---|
| Customer Auth | Customer `customer_id` for ticket attribution |
| Order Module | `order_id` context on ORDER category tickets |
| Dispute Module (STORY-002) | Dispute tickets linked from ORDER tickets |
| SLA Management (STORY-003) | SLA policies and breach detection |
| Agent Management (STORY-004) | Agent assignment and capacity checks |
| Knowledge Base (STORY-005) | Canned response retrieval |
| Notification Engine | CSAT survey; SLA breach alerts; escalation alerts |
| Automation Engine | Auto-escalation on SLA breach |

---

## Notes

- Ticket `status = AWAITING_CUSTOMER` (optional intermediate state) pauses the SLA timer; this state is set when the agent sends a reply and is waiting for customer response.
- `channel = PHONE` tickets are created manually by admin; no inbound phone integration in v1.
- CSAT scores feed into agent performance metrics (STORY-004) and platform support satisfaction KPIs.
