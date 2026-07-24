# STORY-003: Campaign Management

| Field | Value |
|---|---|
| Story ID | EPIC-013-STORY-003 |
| Epic | EPIC-013 Marketing and Growth |
| Title | Campaign Management |
| Priority | P1 |
| Status | Planned |
| Created | 2026-07-24 |
| Last Updated | 2026-07-24 |

---

## Overview

Campaign Management enables the Admin HQ marketing team to plan, launch, monitor, and analyse omnichannel outreach campaigns across Push Notifications (FCM), SMS (DLT-compliant), Email, and WhatsApp (Meta-approved templates). Each campaign is targeted to a customer segment, budgeted, and tracked through a full funnel: sent ? delivered ? opened ? clicked ? converted. Conversion attribution assigns GMV from orders placed within 48 hours of a campaign interaction. Revenue ROI and per-channel cost economics are surfaced in the campaign detail view so the team can optimise spend across channels. Budget caps protect against overspend, automatically pausing campaigns when the cap is reached.

---

## User Roles

| Role | Capability |
|---|---|
| `admin_super` | Full CRUD; launch/pause/resume all campaigns |
| `admin_operations` | Create, edit campaigns; launch/pause/resume |
| `admin_finance` | Read-only; cost and revenue analytics |

---

## Business Rules

1. **Immutable after launch** - a campaign cannot be edited (name, channel, segment, body, budget) after it transitions to RUNNING or COMPLETED status; changes require creating a new campaign.
2. **DLT compliance** - SMS campaigns must use a pre-registered DLT template ID; the template body stored in `message_template_id` is used verbatim without modification.
3. **WhatsApp compliance** - WhatsApp campaigns must use a Meta pre-approved template stored via `message_template_id`; no freeform message body is allowed.
4. **Audience snapshot at launch** - segment membership is resolved at the moment of launch (not at creation time); the `sent_count` reflects the actual recipients at launch.
5. **Conversion attribution window** - an order is attributed to a campaign if placed within 48 hours of the customer's last interaction (delivery, open, or click) with that campaign.
6. **Budget cap auto-pause** - when cumulative send cost reaches `budget_cap`, the campaign transitions to PAUSED; admin is notified via in-app and email.
7. **Cost estimation before launch** - the admin can call the cost-estimate endpoint before creating a campaign to preview recipients and cost.
8. **Push via FCM** - push campaigns use Firebase Cloud Messaging; `message_template_id` is not required; `subject` and `body` fields are used directly.
9. **ROI calculation** - `roi_pct = ((revenue_attributed ? campaign_cost) / campaign_cost) - 100`.
10. **Campaign statuses** - valid transitions: `DRAFT ? SCHEDULED ? RUNNING ? PAUSED ? RUNNING ? COMPLETED`; or `DRAFT ? RUNNING` (immediate launch).

---

## API Endpoints

### 1. List Campaigns (Admin)

```
GET /api/v1/admin/campaigns
Authorization: Bearer JWT (admin_super | admin_operations | admin_finance)
```

**Query Parameters**

| Parameter | Type | Description |
|---|---|---|
| `status` | string | `DRAFT`, `SCHEDULED`, `RUNNING`, `PAUSED`, `COMPLETED` |
| `channel` | string | `PUSH`, `SMS`, `EMAIL`, `WHATSAPP` |
| `page` | integer | Default 1 |
| `limit` | integer | Default 20 |
| `sort` | string | e.g. `scheduled_at`, `conversions` |
| `order` | string | `asc` or `desc` |

**Response 200**
```json
{
  "success": true,
  "data": {
    "campaigns": [
      {
        "id": "camp_uuid_001",
        "name": "Monsoon Reactivation - Dormant Users",
        "channel": "WHATSAPP",
        "target_segment": "DORMANT",
        "sent_count": 12400,
        "open_rate": 38.2,
        "ctr": 8.5,
        "conversions": 620,
        "revenue_attributed_rs": 186000,
        "roi_pct": 620,
        "status": "COMPLETED",
        "scheduled_at": "2026-07-20T10:00:00Z"
      }
    ]
  },
  "meta": { "page": 1, "limit": 20, "total": 32 }
}
```

---

### 2. Create Campaign (Admin)

```
POST /api/v1/admin/campaigns
Authorization: Bearer JWT (admin_super | admin_operations)
Content-Type: application/json
```

**Request Body**
```json
{
  "name": "Monsoon Reactivation - Dormant Users",
  "channel": "WHATSAPP",
  "segment_id": "seg_uuid_dormant",
  "message_template_id": "tmpl_uuid_wa_001",
  "subject": null,
  "body": null,
  "cta_label": "Order Now",
  "cta_link": "https://app.nammamedmate.com/offers",
  "scheduled_at": "2026-07-25T10:00:00Z",
  "estimated_cost": 12400,
  "budget_cap": 15000
}
```

**Response 201**
```json
{
  "success": true,
  "data": {
    "id": "camp_uuid_001",
    "name": "Monsoon Reactivation - Dormant Users",
    "status": "SCHEDULED",
    "created_at": "2026-07-24T09:00:00Z"
  }
}
```

**Error Responses**

| HTTP | Error Code | Description |
|---|---|---|
| 422 | `INVALID_SEGMENT` | `segment_id` does not exist |
| 422 | `INVALID_TEMPLATE` | `message_template_id` not found or not approved |
| 422 | `CHANNEL_TEMPLATE_REQUIRED` | SMS or WhatsApp campaign missing template |
| 422 | `INVALID_BUDGET` | `budget_cap` ? 0 |

---

### 3. Get Campaign Detail (Admin)

```
GET /api/v1/admin/campaigns/:id
Authorization: Bearer JWT (admin_super | admin_operations | admin_finance)
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "id": "camp_uuid_001",
    "name": "Monsoon Reactivation - Dormant Users",
    "channel": "WHATSAPP",
    "segment_id": "seg_uuid_dormant",
    "status": "COMPLETED",
    "funnel": {
      "sent": 12400,
      "delivered": 12185,
      "opened": 4655,
      "clicked": 1054,
      "converted": 620
    },
    "economics": {
      "total_cost_rs": 25800,
      "revenue_attributed_rs": 186000,
      "roi_pct": 620.9
    },
    "timeline": [
      { "event": "CREATED", "at": "2026-07-24T09:00:00Z", "actor": "admin_uuid_001" },
      { "event": "LAUNCHED", "at": "2026-07-25T10:00:00Z", "actor": "admin_uuid_001" },
      { "event": "COMPLETED", "at": "2026-07-25T10:48:00Z", "actor": "SYSTEM" }
    ]
  }
}
```

---

### 4. Launch Campaign (Admin)

```
POST /api/v1/admin/campaigns/:id/launch
Authorization: Bearer JWT (admin_super | admin_operations)
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "id": "camp_uuid_001",
    "status": "RUNNING",
    "launched_at": "2026-07-25T10:00:00Z",
    "estimated_recipients": 12400
  }
}
```

**Error Responses**

| HTTP | Error Code | Description |
|---|---|---|
| 409 | `CAMPAIGN_ALREADY_RUNNING` | Campaign already in RUNNING state |
| 409 | `CAMPAIGN_COMPLETED` | Cannot relaunch a completed campaign |

---

### 5. Pause Campaign (Admin)

```
POST /api/v1/admin/campaigns/:id/pause
Authorization: Bearer JWT (admin_super | admin_operations)
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "id": "camp_uuid_001",
    "status": "PAUSED",
    "paused_at": "2026-07-25T10:15:00Z"
  }
}
```

---

### 6. Resume Campaign (Admin)

```
POST /api/v1/admin/campaigns/:id/resume
Authorization: Bearer JWT (admin_super | admin_operations)
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "id": "camp_uuid_001",
    "status": "RUNNING",
    "resumed_at": "2026-07-25T10:30:00Z"
  }
}
```

---

### 7. Get Campaign Cost Estimate (Admin)

```
GET /api/v1/admin/campaigns/cost-estimate
Authorization: Bearer JWT (admin_super | admin_operations)
```

**Query Parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `channel` | string | Yes | `PUSH`, `SMS`, `EMAIL`, `WHATSAPP` |
| `segment_id` | string | Yes | Target segment UUID |
| `message_length` | integer | No | Character count (for SMS tier pricing) |

**Response 200**
```json
{
  "success": true,
  "data": {
    "estimated_recipients": 12400,
    "estimated_cost_paise": 2580000,
    "cost_per_recipient_paise": 208,
    "channel_rate_card": {
      "WHATSAPP": "Rs 0.85 per message (utility template)"
    }
  }
}
```

---

## Data Model

### Campaign

| Field | Type | Constraints | Description |
|---|---|---|---|
| `id` | UUID v4 | PK | Internal identifier |
| `name` | VARCHAR(200) | NOT NULL | Campaign display name |
| `channel` | ENUM | NOT NULL | `PUSH`, `SMS`, `EMAIL`, `WHATSAPP` |
| `segment_id` | UUID | FK ? segments | Target segment |
| `message_template_id` | UUID | NULLABLE | WA/SMS pre-approved template |
| `subject` | VARCHAR(200) | NULLABLE | Email subject line |
| `body` | TEXT | NULLABLE | Push/Email body |
| `cta_label` | VARCHAR(80) | NULLABLE | CTA button label |
| `cta_link` | TEXT | NULLABLE | CTA destination URL |
| `scheduled_at` | TIMESTAMPTZ | NULLABLE | Null = immediate launch |
| `launched_at` | TIMESTAMPTZ | NULLABLE | Actual launch time |
| `completed_at` | TIMESTAMPTZ | NULLABLE | Completion time |
| `estimated_cost_rs` | DECIMAL(12,2) | NULLABLE | Pre-launch estimate |
| `budget_cap_rs` | DECIMAL(12,2) | NULLABLE | Auto-pause threshold |
| `actual_spend_rs` | DECIMAL(12,2) | DEFAULT 0 | Running spend |
| `sent_count` | INTEGER | DEFAULT 0 | Messages sent |
| `delivered_count` | INTEGER | DEFAULT 0 | Confirmed deliveries |
| `opened_count` | INTEGER | DEFAULT 0 | Opens tracked |
| `clicked_count` | INTEGER | DEFAULT 0 | CTA clicks |
| `converted_count` | INTEGER | DEFAULT 0 | Attributed orders |
| `revenue_attributed_rs` | DECIMAL(14,2) | DEFAULT 0 | GMV from conversions |
| `status` | ENUM | DEFAULT DRAFT | `DRAFT`, `SCHEDULED`, `RUNNING`, `PAUSED`, `COMPLETED` |
| `created_by` | UUID | FK ? admin_users | Creator |
| `created_at` | TIMESTAMPTZ | DEFAULT NOW() | Creation timestamp |
| `updated_at` | TIMESTAMPTZ | DEFAULT NOW() | Last update |

---

## Acceptance Criteria

1. Admin creates a WHATSAPP campaign with a valid template; it is created in DRAFT status.
2. Cost estimate for segment `DORMANT` (12,400 members) on WHATSAPP channel returns `estimated_recipients = 12400` and `estimated_cost_paise > 0`.
3. Launch endpoint transitions status from SCHEDULED ? RUNNING and records `launched_at` timestamp.
4. Campaign with `budget_cap` transitions to PAUSED when `actual_spend_rs ? budget_cap_rs`, and admin receives notification.
5. Conversion attribution: an order placed 24 hours after a campaign click is counted as `converted`; an order placed 49 hours after is not.
6. Attempting to edit a RUNNING campaign returns HTTP 409 `CAMPAIGN_ALREADY_RUNNING`.
7. SMS campaign created without `message_template_id` returns HTTP 422 `CHANNEL_TEMPLATE_REQUIRED`.
8. `roi_pct` in campaign detail = `((revenue_attributed ? actual_spend) / actual_spend) - 100` and matches arithmetic.
9. Funnel in campaign detail has `sent ? delivered ? opened ? clicked ? converted`.
10. Paused campaign can be resumed; RUNNING status is restored.

---

## Dependencies

| Dependency | Description |
|---|---|
| Customer Segmentation (STORY-004) | Segment membership resolution at launch |
| WhatsApp Business API | Meta-approved template management |
| SMS Gateway | DLT template ID registration |
| FCM / Push Service | Push delivery infrastructure |
| Email Service | SMTP / transactional email provider |
| Order Module | Conversion attribution window check |
| Notification Engine | Admin budget-cap alerts |

---

## Notes

- Campaign send is an async job; `sent_count` increments as messages are dispatched, not at launch.
- Delivery receipts from WhatsApp / SMS providers are ingested via webhooks to update `delivered_count`.
- Open tracking for EMAIL uses a 1-1 pixel image; for PUSH it is derived from notification open events.
- DLT entity ID and template ID are stored in the SMS gateway configuration, not per-campaign.
