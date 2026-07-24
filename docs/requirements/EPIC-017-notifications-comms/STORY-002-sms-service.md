# STORY-002: SMS Service

| Field        | Value                                     |
| ------------ | ----------------------------------------- |
| Story ID     | EPIC-017-STORY-002                        |
| Epic         | EPIC-017 Notifications and Communications |
| Title        | SMS Service                               |
| Priority     | P1                                        |
| Status       | In Development                            |
| Role         | Internal service + admin_super            |
| Last Updated | 2026-07-24                                |

## Overview

The SMS Service provides reliable OTP and transactional SMS delivery through MSG91 as the primary provider and Twilio as the fallback. All templates must be pre-registered on the Distributed Ledger Technology (DLT) portal as mandated by TRAI regulations in India. The service enforces DND (Do Not Disturb) registry checks for promotional messages, tracks per-message costs, and maintains delivery logs for debugging and billing. OTP messages are routed through the fast transactional route to minimize delivery latency.

## User Roles

| Role              | Access                                           |
| ----------------- | ------------------------------------------------ |
| Internal services | Call SMS send endpoint (service-to-service auth) |
| admin_super       | View and create templates; view delivery logs    |
| admin_operations  | View delivery logs                               |

## Business Rules

1. **DLT Mandatory Compliance**: Every outgoing SMS must use a DLT-registered template. The `template_id` in the send request must map to a record in the `sms_templates` table with a valid `dlt_template_id`. Sending without a valid DLT template results in a 422 error.
2. **OTP Route Priority**: OTP category messages are sent via MSG91's "OTP" route which has guaranteed throughput and bypasses carrier batching. OTP codes are 6-digit numeric, expire in 10 minutes.
3. **Promotional DND Check**: Before sending a `PROMOTIONAL` SMS, the platform must verify the recipient number is not on the TRAI DND registry (via MSG91's DND check API). Numbers on DND skip SMS silently; the log records `status: SKIPPED_DND`.
4. **Sender ID**: All outgoing SMS use the 6-character sender ID `NMMATE` (registered on DLT portal). If carrier-specific sender ID changes are required, they are managed centrally in the SMS config table.
5. **Fallback to Twilio**: If MSG91 returns a non-2xx response or times out after 5 seconds, the service automatically retries via Twilio. The fallback attempt is logged as a separate log entry referencing the original attempt.
6. **Template Variables**: Templates use `{{1}}`, `{{2}}` variable placeholders (MSG91/DLT standard). The `variables` object in the send request maps positional indices to values.
7. **Cost Tracking**: Each successful SMS send records the cost in the `sms_cost_log` table. MSG91 rate: Rs 0.12/SMS; Twilio rate: Rs 0.20/SMS. Costs are aggregated for monthly billing review.
8. **Phone Number Format**: All phone numbers must be in E.164 format (e.g., `+919876543210`). The send endpoint validates format before calling the provider.
9. **No Promotional SMS After 21:00 IST**: Promotional SMS are blocked between 21:00 and 09:00 IST regardless of system-level triggers. The endpoint returns `422 PROMOTIONAL_TIME_RESTRICTED` for requests in this window.
10. **Template Deactivation**: Deactivating a template (`is_active: false`) prevents it from being used in new send requests. Existing in-flight deliveries are not affected.

## API Endpoints

### POST /api/v1/notifications/sms/send

Internal endpoint to send an SMS to a recipient.

**Auth**: Service-to-service JWT (internal only)

**Request Body**

```json
{
	"to_phone": "+919876543210",
	"template_id": "OTP_VERIFICATION",
	"variables": {
		"1": "482910",
		"2": "10"
	},
	"priority": "OTP"
}
```

**Response 200**

```json
{
	"success": true,
	"data": {
		"log_id": "uuid-sms-log-1",
		"to_phone": "+919876543210",
		"template_id": "OTP_VERIFICATION",
		"provider": "MSG91",
		"provider_message_id": "msg91-abc123",
		"status": "SENT",
		"cost_rs": 0.12,
		"sent_at": "2026-07-24T08:20:00Z"
	},
	"meta": {}
}
```

**Error Table**

| HTTP Code | Error Code                  | Condition                             |
| --------- | --------------------------- | ------------------------------------- |
| 400       | INVALID_PHONE_FORMAT        | Phone not in E.164 format             |
| 422       | TEMPLATE_NOT_FOUND          | template_id not in database           |
| 422       | TEMPLATE_INACTIVE           | Template is deactivated               |
| 422       | DLT_TEMPLATE_MISSING        | Template has no dlt_template_id       |
| 422       | PROMOTIONAL_TIME_RESTRICTED | Promotional SMS outside allowed hours |
| 503       | ALL_PROVIDERS_FAILED        | Both MSG91 and Twilio failed          |

---

### GET /api/v1/admin/notifications/sms/templates

List all DLT-registered SMS templates.

**Auth**: Bearer JWT - `admin_super`

**Query Parameters**

| Parameter | Type    | Required | Description                     |
| --------- | ------- | -------- | ------------------------------- |
| category  | string  | No       | OTP, TRANSACTIONAL, PROMOTIONAL |
| is_active | boolean | No       | Filter active/inactive          |

**Response 200**

```json
{
	"success": true,
	"data": {
		"templates": [
			{
				"template_id": "OTP_VERIFICATION",
				"content": "Your Namma MedMate OTP is {{1}}. Valid for {{2}} minutes. Do not share with anyone. - NMMATE",
				"category": "OTP",
				"dlt_template_id": "1007164875432101",
				"sender_id": "NMMATE",
				"is_active": true,
				"created_at": "2026-01-15T10:00:00Z"
			},
			{
				"template_id": "ORDER_CONFIRMED",
				"content": "Order #{{1}} confirmed. Medicines will be delivered to {{2}} by {{3}}. Track: {{4}} - NMMATE",
				"category": "TRANSACTIONAL",
				"dlt_template_id": "1007164875432102",
				"sender_id": "NMMATE",
				"is_active": true,
				"created_at": "2026-01-15T10:00:00Z"
			}
		]
	},
	"meta": {}
}
```

---

### POST /api/v1/admin/notifications/sms/templates

Register a new SMS template (must already be registered on DLT portal first).

**Auth**: Bearer JWT - `admin_super`

**Request Body**

```json
{
	"template_id": "REFUND_PROCESSED",
	"content": "Refund of Rs {{1}} for order #{{2}} processed. It will reflect in {{3}} working days. - NMMATE",
	"category": "TRANSACTIONAL",
	"dlt_template_id": "1007164875432115",
	"sender_id": "NMMATE"
}
```

**Response 201**

```json
{
	"success": true,
	"data": {
		"template_id": "REFUND_PROCESSED",
		"category": "TRANSACTIONAL",
		"is_active": true,
		"created_at": "2026-07-24T01:45:00Z"
	},
	"meta": {}
}
```

**Error Table**

| HTTP Code | Error Code              | Condition                                     |
| --------- | ----------------------- | --------------------------------------------- |
| 409       | TEMPLATE_ALREADY_EXISTS | template_id already registered                |
| 422       | INVALID_CATEGORY        | category not in OTP/TRANSACTIONAL/PROMOTIONAL |

---

### GET /api/v1/admin/notifications/sms/logs

Retrieve SMS delivery logs.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Query Parameters**

| Parameter   | Type    | Required | Description                                   |
| ----------- | ------- | -------- | --------------------------------------------- |
| to_phone    | string  | No       | Filter by recipient phone                     |
| template_id | string  | No       | Filter by template                            |
| status      | string  | No       | SENT, DELIVERED, FAILED, EXPIRED, SKIPPED_DND |
| date_from   | string  | No       | ISO 8601                                      |
| date_to     | string  | No       | ISO 8601                                      |
| page        | integer | No       | Default 1                                     |
| limit       | integer | No       | Default 20                                    |

**Response 200**

```json
{
	"success": true,
	"data": {
		"logs": [
			{
				"log_id": "uuid-sms-log-1",
				"to_phone": "+919876543210",
				"template_id": "OTP_VERIFICATION",
				"category": "OTP",
				"provider": "MSG91",
				"provider_message_id": "msg91-abc123",
				"fallback_used": false,
				"status": "DELIVERED",
				"cost_rs": 0.12,
				"sent_at": "2026-07-24T08:20:00Z",
				"delivered_at": "2026-07-24T08:20:04Z",
				"error_message": null
			}
		]
	},
	"meta": { "page": 1, "limit": 20, "total": 8420 }
}
```

---

## Data Models

### sms_templates

| Column          | Type        | Notes                           |
| --------------- | ----------- | ------------------------------- |
| template_id     | VARCHAR(60) | PK - slug                       |
| content         | TEXT        | Template with {{n}} variables   |
| category        | VARCHAR(15) | OTP, TRANSACTIONAL, PROMOTIONAL |
| dlt_template_id | VARCHAR(20) | TRAI DLT portal registration ID |
| sender_id       | VARCHAR(6)  | NMMATE                          |
| is_active       | BOOLEAN     |                                 |
| created_by      | UUID        | FK ? admin_users                |
| created_at      | TIMESTAMPTZ |                                 |

### sms_delivery_logs

| Column              | Type         | Notes                                         |
| ------------------- | ------------ | --------------------------------------------- |
| id                  | UUID         | PK                                            |
| to_phone            | VARCHAR(15)  | E.164 format                                  |
| template_id         | VARCHAR(60)  | FK ? sms_templates                            |
| variables           | JSONB        | Variable values used                          |
| provider            | VARCHAR(10)  | MSG91, TWILIO                                 |
| provider_message_id | VARCHAR(100) | Provider's message ID                         |
| fallback_used       | BOOLEAN      | True if Twilio used after MSG91 failure       |
| status              | VARCHAR(15)  | SENT, DELIVERED, FAILED, EXPIRED, SKIPPED_DND |
| cost_rs             | DECIMAL(6,4) | Per-SMS cost                                  |
| sent_at             | TIMESTAMPTZ  |                                               |
| delivered_at        | TIMESTAMPTZ  | Nullable                                      |
| error_message       | TEXT         | Nullable                                      |

## Acceptance Criteria

1. **AC-001**: POST /sms/send with a PROMOTIONAL template between 21:00-09:00 IST returns `422 PROMOTIONAL_TIME_RESTRICTED`.
2. **AC-002**: POST /sms/send with phone number in non-E.164 format (e.g., `9876543210`) returns `400 INVALID_PHONE_FORMAT`.
3. **AC-003**: POST /sms/send when MSG91 times out automatically retries via Twilio; log entry has `fallback_used: true` and `provider: TWILIO`.
4. **AC-004**: POST /sms/send for PROMOTIONAL type for a DND-registered number logs `status: SKIPPED_DND` without calling MSG91.
5. **AC-005**: GET /templates returns only `is_active: true` templates when `?is_active=true` filter is applied.
6. **AC-006**: POST /templates with a `template_id` that already exists returns `409 TEMPLATE_ALREADY_EXISTS`.
7. **AC-007**: GET /logs with `?template_id=OTP_VERIFICATION` returns only OTP verification SMS logs.
8. **AC-008**: Monthly cost report (from `sms_delivery_logs`) aggregates to match MSG91 billing portal within Rs 5 tolerance.

## Dependencies

| Dependency         | Type              | Notes                               |
| ------------------ | ----------------- | ----------------------------------- |
| MSG91              | External provider | Primary SMS delivery                |
| Twilio             | External provider | Fallback SMS delivery               |
| TRAI DLT Portal    | Compliance        | Template registration               |
| EPIC-017-STORY-005 | Gate              | Preference check (promotional only) |
| EPIC-002 Auth      | Consumer          | OTP delivery                        |

## Notes

- DLT registration takes 2-5 business days after template submission. New templates cannot be used in production until DLT approval; include `dlt_template_id` only after receiving the DLT portal confirmation number.
- MSG91 delivery reports are received via webhook (POST callback from MSG91); the webhook updates `delivered_at` in the log.
- The 6-character sender ID `NMMATE` is company-wide. Multiple sender IDs are not planned in V1.
