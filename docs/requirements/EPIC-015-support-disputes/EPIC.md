# EPIC-015: Support and Disputes

| Field | Value |
|---|---|
| Epic ID | EPIC-015 |
| Epic Name | Support and Disputes |
| Folder | `docs/requirements/EPIC-015-support-disputes/` |
| Status | In Progress |
| Owner | Product - Operations & CX Team |
| Created | 2026-07-24 |
| Last Updated | 2026-07-24 |

---

## Overview

EPIC-015 covers the complete customer experience (CX) infrastructure for Namma MedMate - from ticket creation through resolution, including SLA enforcement, dispute adjudication, agent management, and a knowledge base to deflect tickets. Support tickets can be raised by customers, pharmacies, or admins across multiple channels (App, Email, Phone, WhatsApp). Disputes are a special class of ticket tied to orders with a structured liability determination workflow (PHARMACY / RIDER / PLATFORM / CUSTOMER). SLA timers are tracked at L1-L4 levels with automated escalation. The knowledge base reduces ticket volume through self-service help articles and canned responses that accelerate agent handling.

---

## Stories

| Story ID | Title | Status | Priority |
|---|---|---|---|
| STORY-001 | Support Ticket Management | Planned | P0 |
| STORY-002 | Dispute Management | Planned | P0 |
| STORY-003 | SLA & Escalation Management | Planned | P1 |
| STORY-004 | Agent Management | Planned | P1 |
| STORY-005 | Knowledge Base | Planned | P2 |

---

## SLA Matrix

| Level | First Response SLA | Escalation Trigger |
|---|---|---|
| L1 | 30 minutes | Auto-escalate to L2 after 30 min breach |
| L2 | 2 hours | Auto-escalate to L3 after 2 hr breach |
| L3 | 8 hours | Auto-escalate to L4 after 8 hr breach |
| L4 | 24 hours | Senior Ops Manager notified |

---

## Dispute Liability Matrix

| Dispute Type | System Recommendation |
|---|---|
| WRONG_ITEMS | PHARMACY |
| MISSING_ITEMS | PHARMACY |
| DAMAGED | PHARMACY |
| NOT_DELIVERED | RIDER (if tracking shows delivered) |
| EXPIRED_MEDICINE | PHARMACY |
| QUALITY | PHARMACY |
| OVERCHARGED | PLATFORM |

---

## Roles Involved

| Role | Access |
|---|---|
| `admin_super` | Full access; override any ticket/dispute |
| `admin_operations` | Manage tickets, disputes, agents |
| `admin_support` | Handle assigned tickets and disputes |
| `customer` | Create tickets, disputes; view own history |
| `pharmacy_owner` | Create pharmacy-side tickets |
| `pharmacy_staff` | Create pharmacy-side tickets |

---

## Key Business Rules (Epic-Level)

1. SLA timer starts on ticket creation; pauses when awaiting customer reply; resumes on customer reply.
2. SLA breach triggers automatic escalation to next level via automation engine.
3. Only one dispute may be raised per order.
4. Refunds ? Rs 200 are auto-processed; amounts > Rs 200 require `admin_support` approval.
5. CSAT survey is sent 24 hours after ticket resolution.
6. Customer reply to a RESOLVED ticket automatically reopens it.

---

## Dependencies

| Dependency | Epic / Module |
|---|---|
| Order Lifecycle | EPIC-004 Orders |
| Finance / Refunds | EPIC-008 Finance |
| Notification Engine | EPIC-010 Notifications |
| Automation Engine | EPIC-011 Automations |
| Customer Auth | EPIC-002 Auth |
| Rider Tracking | EPIC-006 Delivery |
