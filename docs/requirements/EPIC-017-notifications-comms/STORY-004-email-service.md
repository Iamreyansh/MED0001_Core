# STORY-004: Email Service

| Field | Value |
|-------|-------|
| Story ID | EPIC-017-STORY-004 |
| Epic | EPIC-017 Notifications and Communications |
| Title | Email Service |
| Priority | P1 |
| Status | In Development |
| Role | Internal service + admin_super |
| Last Updated | 2026-07-24 |

## Overview

The Email Service provides transactional and campaign email delivery through SendGrid as the primary provider, with AWS SES as fallback. It manages HTML email templates with Handlebars-style variable substitution, supports PDF attachments for invoices and receipts, and tracks open and click events via tracking pixels and redirect links. Hard bounces and spam reports automatically trigger unsubscribes. The service enforces SPF, DKIM, and DMARC for the sending domain to maintain deliverability. Transactional emails (order, payment, account-critical) bypass unsubscribe checks.

## User Roles

| Role | Access |
|------|--------|
| Internal services | Call email send endpoint (service-to-service) |
| admin_super | Manage templates; view delivery logs |
| admin_operations | View delivery logs |

## Business Rules

1. **HTML + Plain Text**: Every email send must include both an HTML body and a plain text body. The plain text version is used by email clients that don't render HTML. If only HTML is provided, the service auto-generates a plain-text version by stripping HTML tags.
2. **Template Variables**: Templates use Handlebars syntax (`{{variable_name}}`). Nested objects (`{{order.id}}`) and conditionals (`{{#if condition}}`) are supported. Undefined variables render as empty strings.
3. **Hard Bounce Handling**: If SendGrid reports a hard bounce for an email address, the address is added to the `email_bounces` table with `bounce_type: HARD` and `is_unsubscribed: true`. No further emails are sent to this address.
4. **Spam Report Handling**: A spam report (user marks email as spam) immediately adds the address to `email_unsubscribes` and halts all future non-transactional emails to that address.
5. **Transactional Bypass**: Emails categorized as `TRANSACTIONAL` (ORDER_CONFIRMATION, REFUND_PROCESSED, PASSWORD_RESET, ACCOUNT_SECURITY) are sent regardless of unsubscribe status. They must include a notice: "You are receiving this because it relates to your account activity."
6. **Promotional Unsubscribe Link**: All `MARKETING` and `LIFECYCLE` category emails must include a one-click unsubscribe link in the footer. The link uses a signed JWT token (7-day expiry) pointing to the public POST /notifications/unsubscribe endpoint.
7. **PDF Attachments**: Attachments are fetched from the provided URL (S3 pre-signed URL) and attached before sending. Maximum attachment size: 10 MB total. If the fetch fails, the email is sent without the attachment and a warning is logged.
8. **SendGrid ? AWS SES Fallback**: If SendGrid returns a 5xx error or times out (10 seconds), the service retries once via AWS SES. Fallback usage is logged in the delivery log.
9. **Open and Click Tracking**: Open events are tracked via a 1-1 pixel GIF hosted on the platform CDN. Click events are tracked by routing all links through a redirect endpoint that logs the click and forwards to the original URL.
10. **Domain Authentication**: SPF, DKIM, and DMARC are configured for the sending domain (`mail.nammamedmate.in`). The DKIM selector used by SendGrid must be validated against DNS before any email is sent.

## API Endpoints

### POST /api/v1/notifications/email/send

Internal endpoint to send an email.

**Auth**: Service-to-service JWT (internal only)

**Request Body**
```json
{
  "to_email": "ravi.kumar@example.com",
  "to_name": "Ravi Kumar",
  "template_id": "ORDER_CONFIRMATION",
  "variables": {
    "customer_name": "Ravi Kumar",
    "order_id": "ORD-8821",
    "order_items": [
      { "name": "Metformin 500mg", "qty": 2, "price": "Rs 84.00" }
    ],
    "total_amount": "Rs 504.00",
    "delivery_address": "12, 5th Cross, Indiranagar, Bangalore - 560038",
    "track_url": "https://app.nammamedmate.in/track/uuid-order-1"
  },
  "attachments": [
    {
      "filename": "invoice_ORD-8821.pdf",
      "url": "https://s3.amazonaws.com/namma-medmate/invoices/ORD-8821.pdf?..."
    }
  ]
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "log_id": "uuid-email-log-1",
    "to_email": "ravi.kumar@example.com",
    "template_id": "ORDER_CONFIRMATION",
    "provider": "SENDGRID",
    "provider_message_id": "sg-abc123",
    "status": "SENT",
    "sent_at": "2026-07-24T08:25:00Z"
  },
  "meta": {}
}
```

**Error Table**

| HTTP Code | Error Code | Condition |
|-----------|-----------|-----------|
| 422 | TEMPLATE_NOT_FOUND | template_id not in database |
| 422 | RECIPIENT_HARD_BOUNCED | Address has a hard bounce record |
| 422 | ATTACHMENT_TOO_LARGE | Total attachment size exceeds 10 MB |
| 503 | ALL_PROVIDERS_FAILED | SendGrid and SES both failed |

---

### GET /api/v1/admin/notifications/email/templates

List email templates.

**Auth**: Bearer JWT - `admin_super`

**Response 200**
```json
{
  "success": true,
  "data": {
    "templates": [
      {
        "id": "ORDER_CONFIRMATION",
        "name": "Order Confirmation",
        "subject": "Your Namma MedMate order #{{order_id}} is confirmed!",
        "category": "TRANSACTIONAL",
        "last_sent": "2026-07-24T08:25:00Z",
        "open_rate_pct": 68.4,
        "click_rate_pct": 24.1
      },
      {
        "id": "SUBSCRIPTION_EXPIRY_WARNING",
        "name": "Subscription Expiry Warning",
        "subject": "Your {{plan_name}} plan expires in {{days_remaining}} days",
        "category": "LIFECYCLE",
        "last_sent": "2026-07-23T06:00:00Z",
        "open_rate_pct": 52.1,
        "click_rate_pct": 31.8
      }
    ]
  },
  "meta": {}
}
```

---

### POST /api/v1/admin/notifications/email/templates

Create or update an email template.

**Auth**: Bearer JWT - `admin_super`

**Request Body**
```json
{
  "name": "Refund Processed",
  "template_id": "REFUND_PROCESSED",
  "subject": "Refund of {{amount}} processed for order #{{order_id}}",
  "html_body": "<!DOCTYPE html><html>...<p>Hi {{customer_name}}, your refund of <strong>{{amount}}</strong> has been processed...</p>...</html>",
  "text_body": "Hi {{customer_name}}, your refund of {{amount}} for order #{{order_id}} has been processed and will reflect in {{working_days}} working days.",
  "category": "TRANSACTIONAL"
}
```

**Response 201**
```json
{
  "success": true,
  "data": {
    "template_id": "REFUND_PROCESSED",
    "category": "TRANSACTIONAL",
    "created_at": "2026-07-24T01:55:00Z"
  },
  "meta": {}
}
```

---

### GET /api/v1/admin/notifications/email/logs

Retrieve email delivery logs.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Query Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| to_email | string | No | Filter by recipient |
| template_id | string | No | Filter by template |
| status | string | No | SENT, DELIVERED, OPENED, CLICKED, BOUNCED, SPAM |
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
        "log_id": "uuid-email-log-1",
        "to_email": "ravi.kumar@example.com",
        "to_name": "Ravi Kumar",
        "template_id": "ORDER_CONFIRMATION",
        "subject": "Your Namma MedMate order #ORD-8821 is confirmed!",
        "provider": "SENDGRID",
        "fallback_used": false,
        "status": "CLICKED",
        "sent_at": "2026-07-24T08:25:00Z",
        "delivered_at": "2026-07-24T08:25:04Z",
        "opened_at": "2026-07-24T09:12:22Z",
        "clicked_at": "2026-07-24T09:12:45Z",
        "error_message": null
      }
    ]
  },
  "meta": { "page": 1, "limit": 20, "total": 8240 }
}
```

---

## Data Models

### email_templates

| Column | Type | Notes |
|--------|------|-------|
| template_id | VARCHAR(60) | PK |
| name | VARCHAR(200) | Display name |
| subject | TEXT | Handlebars subject line |
| html_body | TEXT | Full HTML template |
| text_body | TEXT | Plain text version |
| category | VARCHAR(15) | TRANSACTIONAL, LIFECYCLE, MARKETING |
| is_active | BOOLEAN | |
| version | INTEGER | Incremented on update |
| created_by | UUID | FK ? admin_users |
| created_at | TIMESTAMPTZ | |
| updated_at | TIMESTAMPTZ | |

### email_delivery_logs

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| to_email | VARCHAR(320) | |
| to_name | VARCHAR(200) | |
| template_id | VARCHAR(60) | FK ? email_templates |
| subject | TEXT | Rendered subject |
| provider | VARCHAR(10) | SENDGRID, SES |
| fallback_used | BOOLEAN | |
| provider_message_id | VARCHAR(200) | |
| status | VARCHAR(15) | SENT, DELIVERED, OPENED, CLICKED, BOUNCED, SPAM |
| sent_at | TIMESTAMPTZ | |
| delivered_at | TIMESTAMPTZ | Nullable |
| opened_at | TIMESTAMPTZ | Nullable |
| clicked_at | TIMESTAMPTZ | Nullable |
| bounce_type | VARCHAR(10) | HARD, SOFT - nullable |
| error_message | TEXT | Nullable |

### email_bounces

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| email | VARCHAR(320) | |
| bounce_type | VARCHAR(10) | HARD, SOFT |
| bounce_reason | TEXT | Provider reason |
| is_unsubscribed | BOOLEAN | True for HARD bounces |
| recorded_at | TIMESTAMPTZ | |

### email_unsubscribes

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| email | VARCHAR(320) | |
| unsubscribe_source | VARCHAR(20) | LINK_CLICK, SPAM_REPORT, MANUAL |
| unsubscribed_at | TIMESTAMPTZ | |
| is_active | BOOLEAN | False if re-subscribed |

## Acceptance Criteria

1. **AC-001**: POST /email/send to a hard-bounced email returns `422 RECIPIENT_HARD_BOUNCED` without calling SendGrid.
2. **AC-002**: POST /email/send for a TRANSACTIONAL template to an unsubscribed email is sent successfully (transactional bypass).
3. **AC-003**: POST /email/send when SendGrid returns 503 automatically retries via AWS SES; log entry has `fallback_used: true`.
4. **AC-004**: Clicking the unsubscribe link in a MARKETING email calls POST /notifications/unsubscribe and adds the address to `email_unsubscribes`.
5. **AC-005**: GET /templates returns `open_rate_pct` and `click_rate_pct` computed from delivery log events.
6. **AC-006**: A spam report from SendGrid webhook immediately marks the email address as unsubscribed.
7. **AC-007**: POST /email/send with attachment URL that returns 404 sends the email without the attachment and logs a warning.
8. **AC-008**: Template with `{{undefined_variable}}` sends email with the variable rendered as an empty string (not as literal `{{undefined_variable}}`).

## Dependencies

| Dependency | Type | Notes |
|-----------|------|-------|
| SendGrid | External | Primary email delivery |
| AWS SES | External | Fallback email delivery |
| AWS S3 | Storage | Attachment source URLs |
| EPIC-005 Finance | Consumer | Invoice/settlement email triggers |
| EPIC-006 Pharmacy | Consumer | KYC status email triggers |
| EPIC-017-STORY-005 | Gate | Preference check for promotional emails |

## Notes

- Email open rates are approximate - Apple Mail Privacy Protection (MPP) pre-fetches tracking pixels, inflating open rates for iOS users. This is a known industry limitation.
- The `text_body` field is required even if generated from HTML stripping. Pure-HTML emails score lower in spam filters.
- Seed transactional templates (ORDER_CONFIRMATION, REFUND_PROCESSED, PASSWORD_RESET, etc.) are populated during platform initialization.
