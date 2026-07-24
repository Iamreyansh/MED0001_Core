# STORY-003: WhatsApp Business API

| Field | Value |
|-------|-------|
| Story ID | EPIC-017-STORY-003 |
| Epic | EPIC-017 Notifications and Communications |
| Title | WhatsApp Business API |
| Priority | P1 |
| Status | In Development |
| Role | Internal service + admin_super |
| Last Updated | 2026-07-24 |

## Overview

The WhatsApp Business API story integrates the Meta Cloud API (WhatsApp Business Platform) to send order updates, payment confirmations, KYC status notifications, and marketing messages to customers and pharmacies. Only Meta-approved message templates can be sent outside a 24-hour customer-initiated conversation window. The service manages template submission, tracks delivery and read receipts via webhooks, handles opt-out signals, and maintains delivery logs. WhatsApp is the highest-engagement channel and is used as the primary rich-message channel for order lifecycle events.

## User Roles

| Role | Access |
|------|--------|
| Internal services | Call WhatsApp send endpoint (service-to-service) |
| admin_super | View templates, submit templates, view logs |
| admin_operations | View logs |
| Webhook endpoint | Public (Meta callback) - no auth, signature verified |

## Business Rules

1. **Approved Templates Only**: Outgoing messages outside a 24-hour customer-initiated session must use a Meta-approved template. Free-form messages (`text` type) can only be sent within the 24-hour window. The send endpoint rejects any attempt to send a non-template message outside this window.
2. **Template Categories**: `UTILITY` templates (order/payment updates) have the lowest per-message cost. `MARKETING` templates are used for campaigns. `AUTHENTICATION` templates for OTP-via-WA (optional for V1). Template category affects Meta billing.
3. **Opt-Out via Webhook**: When a customer replies with `STOP` or `0` (standard WA opt-out), the Meta webhook fires an opt-out event. The platform records this in `whatsapp_optouts` and future messages to this number are silently skipped.
4. **Opt-Out via App**: Customers can also opt out from within the Namma MedMate app (via STORY-005 preference toggle). Both in-app and WA-native opt-outs are checked before sending.
5. **Delivery Receipts**: Meta sends delivery receipt webhooks (`sent`, `delivered`, `read`). The platform updates the delivery log on each event.
6. **Template Rejection Handling**: If Meta rejects a submitted template, the `status` is set to `REJECTED` and `rejection_reason` is stored. The admin team is notified via email so the template can be revised and resubmitted.
7. **Phone Number Registration**: The WhatsApp Business Account (WABA) phone number must be registered with Meta Business Manager. The phone number used for sending is stored in config and cannot be changed at runtime.
8. **Message Components**: Templates support `HEADER` (text, image, document), `BODY` (with variables), `FOOTER` (static text), and `BUTTONS` (call-to-action URL or quick reply). Component structure must match the approved template structure exactly.
9. **24-Hour Window Reset**: Each customer message (inbound) resets the 24-hour session window. The platform tracks `last_customer_message_at` per phone number to determine session status.
10. **Webhook Signature Verification**: All incoming webhooks from Meta must be verified using the `X-Hub-Signature-256` header (HMAC-SHA256 with app secret). Unverified webhooks are rejected with 403.

## API Endpoints

### POST /api/v1/notifications/whatsapp/send

Internal endpoint to send a WhatsApp template message.

**Auth**: Service-to-service JWT (internal only)

**Request Body**
```json
{
  "to_phone": "+919876543210",
  "template_name": "ORDER_CONFIRMED",
  "template_language": "en",
  "components": [
    {
      "type": "header",
      "parameters": [
        { "type": "text", "text": "ORD-8821" }
      ]
    },
    {
      "type": "body",
      "parameters": [
        { "type": "text", "text": "Ravi Kumar" },
        { "type": "text", "text": "Apollo Pharmacy, Indiranagar" },
        { "type": "text", "text": "Rs 504.00" },
        { "type": "text", "text": "45 minutes" }
      ]
    }
  ]
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "log_id": "uuid-wa-log-1",
    "to_phone": "+919876543210",
    "template_name": "ORDER_CONFIRMED",
    "wa_message_id": "wamid.abc123XYZ",
    "status": "SENT",
    "sent_at": "2026-07-24T08:22:00Z"
  },
  "meta": {}
}
```

**Error Table**

| HTTP Code | Error Code | Condition |
|-----------|-----------|-----------|
| 400 | INVALID_PHONE_FORMAT | Phone not in E.164 format |
| 422 | TEMPLATE_NOT_FOUND | template_name not in approved templates |
| 422 | TEMPLATE_NOT_APPROVED | Template status is PENDING or REJECTED |
| 422 | RECIPIENT_OPTED_OUT | Customer has opted out from WhatsApp |
| 422 | COMPONENT_MISMATCH | Component parameters don't match template |
| 503 | META_API_UNAVAILABLE | Meta Cloud API returned error |

---

### GET /api/v1/admin/notifications/whatsapp/templates

List all WhatsApp message templates and their Meta approval status.

**Auth**: Bearer JWT - `admin_super`

**Query Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| category | string | No | UTILITY, MARKETING, AUTHENTICATION |
| status | string | No | APPROVED, PENDING, REJECTED |

**Response 200**
```json
{
  "success": true,
  "data": {
    "templates": [
      {
        "template_name": "ORDER_CONFIRMED",
        "category": "UTILITY",
        "language": "en",
        "status": "APPROVED",
        "body_text": "Hi {{1}}, your order from {{2}} for {{3}} has been confirmed. Estimated delivery: {{4}}.",
        "buttons": [
          { "type": "URL", "text": "Track Order", "url": "https://app.nammamedmate.in/track/{{1}}" }
        ],
        "last_used_at": "2026-07-24T08:22:00Z",
        "rejection_reason": null
      },
      {
        "template_name": "PROMO_SUMMER_SALE",
        "category": "MARKETING",
        "language": "en",
        "status": "REJECTED",
        "body_text": "Get 20% off all vitamins this week! Use code SUMMER20.",
        "buttons": [],
        "last_used_at": null,
        "rejection_reason": "Content policy violation: discount percentage claims require proof"
      }
    ]
  },
  "meta": {}
}
```

---

### POST /api/v1/admin/notifications/whatsapp/templates

Submit a new template to Meta for approval.

**Auth**: Bearer JWT - `admin_super`

**Request Body**
```json
{
  "name": "REORDER_REMINDER",
  "category": "UTILITY",
  "language": "en",
  "body": "Hi {{1}}, it's time to reorder your {{2}}. You last ordered it {{3}} days ago. Tap below to reorder in one click!",
  "header": {
    "format": "TEXT",
    "text": "Medicine Refill Reminder"
  },
  "footer": "Reply STOP to opt out",
  "buttons": [
    { "type": "URL", "text": "Reorder Now", "url": "https://app.nammamedmate.in/reorder/{{1}}" }
  ]
}
```

**Response 202**
```json
{
  "success": true,
  "data": {
    "template_name": "REORDER_REMINDER",
    "status": "PENDING",
    "submitted_at": "2026-07-24T01:50:00Z",
    "estimated_review_days": 2
  },
  "meta": {}
}
```

**Error Table**

| HTTP Code | Error Code | Condition |
|-----------|-----------|-----------|
| 409 | TEMPLATE_NAME_EXISTS | template_name already registered |
| 422 | INVALID_CATEGORY | category not in allowed set |
| 422 | INVALID_LANGUAGE | language code not supported |

---

### GET /api/v1/admin/notifications/whatsapp/logs

Retrieve WhatsApp delivery logs.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Query Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| to_phone | string | No | Filter by recipient |
| template_name | string | No | Filter by template |
| status | string | No | SENT, DELIVERED, READ, FAILED |
| date_from | string | No | ISO 8601 |
| date_to | string | No | ISO 8601 |
| page | integer | No | Default 1 |

**Response 200**
```json
{
  "success": true,
  "data": {
    "logs": [
      {
        "log_id": "uuid-wa-log-1",
        "to_phone": "+919876543210",
        "template_name": "ORDER_CONFIRMED",
        "category": "UTILITY",
        "wa_message_id": "wamid.abc123XYZ",
        "status": "READ",
        "cost_rs": 0.85,
        "sent_at": "2026-07-24T08:22:00Z",
        "delivered_at": "2026-07-24T08:22:08Z",
        "read_at": "2026-07-24T08:25:41Z",
        "error_message": null
      }
    ]
  },
  "meta": { "page": 1, "limit": 20, "total": 12480 }
}
```

---

### POST /api/v1/notifications/whatsapp/webhook

Meta webhook handler for delivery receipts and opt-out signals.

**Auth**: Public endpoint - Meta webhook verification via `X-Hub-Signature-256`

**Request Body** (Meta format - delivery receipt example)
```json
{
  "object": "whatsapp_business_account",
  "entry": [{
    "id": "WABA_ID",
    "changes": [{
      "value": {
        "messaging_product": "whatsapp",
        "statuses": [{
          "id": "wamid.abc123XYZ",
          "recipient_id": "919876543210",
          "status": "delivered",
          "timestamp": "1721808128"
        }]
      },
      "field": "messages"
    }]
  }]
}
```

**Response 200**
```json
{ "success": true }
```

**Error Table**

| HTTP Code | Error Code | Condition |
|-----------|-----------|-----------|
| 403 | INVALID_SIGNATURE | X-Hub-Signature-256 verification failed |

---

## Data Models

### whatsapp_templates

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| template_name | VARCHAR(100) | Unique identifier |
| category | VARCHAR(15) | UTILITY, MARKETING, AUTHENTICATION |
| language | VARCHAR(10) | Language code |
| status | VARCHAR(10) | APPROVED, PENDING, REJECTED |
| body_text | TEXT | Template body with variables |
| header_json | JSONB | Header component definition |
| footer_text | VARCHAR(200) | |
| buttons_json | JSONB | Button definitions |
| meta_template_id | VARCHAR(100) | Meta's internal template ID |
| rejection_reason | TEXT | Nullable |
| submitted_at | TIMESTAMPTZ | |
| approved_at | TIMESTAMPTZ | |
| last_used_at | TIMESTAMPTZ | |

### whatsapp_delivery_logs

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| to_phone | VARCHAR(15) | E.164 format |
| template_name | VARCHAR(100) | FK ? whatsapp_templates |
| components_json | JSONB | Actual parameters sent |
| wa_message_id | VARCHAR(200) | Meta's WAMID |
| status | VARCHAR(10) | SENT, DELIVERED, READ, FAILED |
| cost_rs | DECIMAL(6,4) | Per-message cost (category-based) |
| sent_at | TIMESTAMPTZ | |
| delivered_at | TIMESTAMPTZ | Nullable |
| read_at | TIMESTAMPTZ | Nullable |
| error_code | VARCHAR(20) | Meta error code if FAILED |
| error_message | TEXT | Nullable |

### whatsapp_optouts

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| phone | VARCHAR(15) | E.164 format |
| optout_source | VARCHAR(10) | WA_REPLY, IN_APP |
| opted_out_at | TIMESTAMPTZ | |
| is_active | BOOLEAN | False if customer has re-opted-in |

## Acceptance Criteria

1. **AC-001**: POST /whatsapp/send for a phone number in `whatsapp_optouts` returns `422 RECIPIENT_OPTED_OUT` without calling Meta API.
2. **AC-002**: POST /whatsapp/webhook with incorrect `X-Hub-Signature-256` returns `403 INVALID_SIGNATURE`.
3. **AC-003**: A delivery receipt webhook updates the log entry's `delivered_at`; a read receipt updates `read_at`.
4. **AC-004**: POST /whatsapp/send with a PENDING or REJECTED template returns `422 TEMPLATE_NOT_APPROVED`.
5. **AC-005**: GET /templates returns `rejection_reason` for REJECTED templates and null for APPROVED ones.
6. **AC-006**: POST /templates/REORDER_REMINDER successfully submits to Meta and returns `status: PENDING`.
7. **AC-007**: An inbound WA "STOP" reply from a customer triggers the opt-out webhook flow and adds the phone to `whatsapp_optouts`.
8. **AC-008**: GET /logs shows cost_rs values: Rs 0.85 for UTILITY templates, Rs 2.00 for MARKETING templates.

## Dependencies

| Dependency | Type | Notes |
|-----------|------|-------|
| Meta WhatsApp Cloud API | External | Message delivery and templates |
| Meta Business Manager | External | WABA registration |
| EPIC-017-STORY-005 | Gate | In-app opt-out check |
| EPIC-001 Order Management | Event source | Order lifecycle triggers |

## Notes

- Required templates to pre-submit before launch: ORDER_CONFIRMED, ORDER_OUT_FOR_DELIVERY, ORDER_DELIVERED, PRESCRIPTION_REJECTED, SETTLEMENT_RELEASED, KYC_APPROVED, KYC_REJECTED, REMINDER_PAYMENT, REFERRAL_REWARD, PRESCRIPTION_REMINDER, REORDER_REMINDER.
- Meta template review typically takes 24-48 hours. Launch readiness requires all UTILITY templates in APPROVED status.
- Per-message cost: Rs 0.85 (UTILITY), Rs 2.00 (MARKETING). Costs updated in config if Meta pricing changes.
