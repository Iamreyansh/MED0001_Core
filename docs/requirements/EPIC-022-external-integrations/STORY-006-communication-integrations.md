# STORY-006: Communication Integrations

| Field | Value |
|-------|-------|
| Story ID | EPIC-022-STORY-006 |
| Epic | EPIC-022 External Integrations |
| Title | Communication Integrations |
| Priority | P1 |
| Status | In Development |
| Role | admin_super |
| Last Updated | 2026-07-24 |

## Overview

The Communication Integrations story provides a central admin UI for monitoring, configuring, and testing all four communication channel integrations: Firebase FCM (push), MSG91/Twilio (SMS), Meta WhatsApp Cloud API (WhatsApp), and SendGrid/AWS SES (email). It surfaces health status, delivery rates, daily and monthly cost tracking, and provides a test send capability for each channel. Admins can update channel configurations and API credentials securely. Automatic fallback between providers (SMS: MSG91 ? Twilio; Email: SendGrid ? SES) is configured here.

## User Roles

| Role | Access |
|------|--------|
| admin_super | Full access; update credentials and config |
| admin_operations | Read health, usage, and logs; run test sends |

## Business Rules

1. **Health Check Interval**: Channel health is checked every 5 minutes by sending a test ping to each provider's API. Status HEALTHY = ping succeeded; DEGRADED = ping succeeded but delivery rate < 95%; DOWN = ping failed or no successful sends in last 30 minutes.
2. **Automatic Fallback**: When the primary SMS provider (MSG91) has `status: DOWN`, the system automatically switches to Twilio. When the primary email provider (SendGrid) has `status: DOWN`, it switches to AWS SES. Fallback is automatic and logged.
3. **Cost Tracking**: Costs are tracked per channel per day using rates defined in the channel config: SMS Rs 0.12/message, WhatsApp UTILITY Rs 0.85/message, WhatsApp MARKETING Rs 2.00/message, Email Rs 0.005/email. Monthly cost reports aggregate daily records.
4. **Daily Send Limits**: Each channel has a configurable `daily_send_limit`. When the limit is reached, new sends are queued (if urgent) or dropped (if promotional). Admin is alerted when 80% of daily limit is reached.
5. **Credential Updates**: When API credentials are updated via PATCH /config, the platform immediately runs a connectivity test with the new credentials before saving. If the test fails, the old credentials are retained and an error is returned.
6. **Credential Masking**: API credentials are never returned in API responses. The `api_credentials` field in GET /config shows only the first 4 characters followed by `****` for display purposes. Full credentials are in AWS Secrets Manager.
7. **Test Message Routing**: POST /test sends a real message to the specified recipient using the live provider configuration. Test messages are labelled in logs with `is_test: true`.
8. **Channel Disable**: An admin can disable a channel via PATCH /config. When disabled, all sends for that channel use the fallback (if configured) or are silently dropped (logged as `CHANNEL_DISABLED`).
9. **Config Audit Log**: Every configuration change (credential update, limit change, enable/disable) is audit-logged with actor, timestamp, and changed fields (credentials masked).
10. **Delivery Rate Computation**: `delivery_rate_pct` = (DELIVERED status messages / SENT status messages) - 100, computed over the last 24 hours from each channel's delivery log.

## API Endpoints

### GET /api/v1/admin/integrations/communications/status

Get health status of all communication channels.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Response 200**
```json
{
  "success": true,
  "data": {
    "channels": [
      {
        "channel": "PUSH",
        "provider": "FIREBASE_FCM",
        "fallback_provider": null,
        "status": "HEALTHY",
        "last_successful_send": "2026-07-24T10:40:00Z",
        "error_rate_pct_24h": 0.8,
        "delivery_rate_pct_24h": 94.2,
        "last_health_check_at": "2026-07-24T10:40:00Z"
      },
      {
        "channel": "SMS",
        "provider": "MSG91",
        "fallback_provider": "TWILIO",
        "status": "DEGRADED",
        "last_successful_send": "2026-07-24T10:38:00Z",
        "error_rate_pct_24h": 6.2,
        "delivery_rate_pct_24h": 89.1,
        "last_health_check_at": "2026-07-24T10:40:00Z"
      },
      {
        "channel": "WHATSAPP",
        "provider": "META_CLOUD_API",
        "fallback_provider": null,
        "status": "HEALTHY",
        "last_successful_send": "2026-07-24T10:41:00Z",
        "error_rate_pct_24h": 0.1,
        "delivery_rate_pct_24h": 98.4,
        "last_health_check_at": "2026-07-24T10:40:00Z"
      },
      {
        "channel": "EMAIL",
        "provider": "SENDGRID",
        "fallback_provider": "AWS_SES",
        "status": "HEALTHY",
        "last_successful_send": "2026-07-24T10:39:00Z",
        "error_rate_pct_24h": 0.3,
        "delivery_rate_pct_24h": 97.8,
        "last_health_check_at": "2026-07-24T10:40:00Z"
      }
    ],
    "overall_status": "DEGRADED",
    "as_of": "2026-07-24T10:41:00Z"
  },
  "meta": {}
}
```

---

### POST /api/v1/admin/integrations/communications/test

Send a test message via a specific channel.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Request Body**
```json
{
  "channel": "SMS",
  "recipient": "+919876543210",
  "test_template": "OTP_VERIFICATION"
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "channel": "SMS",
    "provider": "MSG91",
    "recipient": "+919876543210",
    "status": "SENT",
    "is_test": true,
    "log_id": "uuid-log-test-1",
    "sent_at": "2026-07-24T10:42:00Z"
  },
  "meta": {}
}
```

**Error Table**

| HTTP Code | Error Code | Condition |
|-----------|-----------|-----------|
| 400 | INVALID_CHANNEL | channel not in SMS/WHATSAPP/PUSH/EMAIL |
| 422 | TEMPLATE_NOT_FOUND | test_template not found for this channel |
| 503 | PROVIDER_UNAVAILABLE | Provider returned error on test send |

---

### GET /api/v1/admin/integrations/communications/usage

Get channel usage and cost statistics.

**Auth**: Bearer JWT - `admin_super`, `admin_operations`

**Query Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| channel | string | No | Filter by channel |

**Response 200**
```json
{
  "success": true,
  "data": {
    "usage": [
      {
        "channel": "SMS",
        "provider": "MSG91",
        "sent_today": 2840,
        "sent_month": 48200,
        "cost_today_rs": 340.80,
        "cost_month_rs": 5784.00,
        "daily_limit": 50000,
        "daily_limit_pct_used": 5.7,
        "delivery_rate_pct": 89.1,
        "fallback_sent_today": 42
      },
      {
        "channel": "WHATSAPP",
        "provider": "META_CLOUD_API",
        "sent_today": 1420,
        "sent_month": 24100,
        "cost_today_rs": 1207.00,
        "cost_month_rs": 20485.00,
        "daily_limit": 20000,
        "daily_limit_pct_used": 7.1,
        "delivery_rate_pct": 98.4,
        "fallback_sent_today": 0
      }
    ]
  },
  "meta": {}
}
```

---

### PATCH /api/v1/admin/integrations/communications/config/:channel

Update channel configuration and credentials.

**Auth**: Bearer JWT - `admin_super` only

**Path Parameters**

| Parameter | Type | Description |
|-----------|------|-------------|
| channel | string | SMS, WHATSAPP, PUSH, EMAIL |

**Request Body**
```json
{
  "is_enabled": true,
  "provider": "MSG91",
  "fallback_provider": "TWILIO",
  "api_credentials": {
    "api_key": "new-msg91-api-key-here",
    "sender_id": "NMMATE"
  },
  "daily_send_limit": 50000
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "channel": "SMS",
    "is_enabled": true,
    "provider": "MSG91",
    "fallback_provider": "TWILIO",
    "api_key_preview": "new-****",
    "daily_send_limit": 50000,
    "connectivity_test_result": "PASSED",
    "updated_by": "uuid-admin-1",
    "updated_at": "2026-07-24T10:45:00Z"
  },
  "meta": {}
}
```

**Error Table**

| HTTP Code | Error Code | Condition |
|-----------|-----------|-----------|
| 403 | FORBIDDEN | Non-admin_super role |
| 404 | CHANNEL_NOT_FOUND | channel not in allowed set |
| 422 | CONNECTIVITY_TEST_FAILED | New credentials failed connectivity test |

---

## Data Models

### communication_channel_configs

| Column | Type | Notes |
|--------|------|-------|
| channel | VARCHAR(10) | PK - PUSH, SMS, WHATSAPP, EMAIL |
| is_enabled | BOOLEAN | |
| provider | VARCHAR(20) | FIREBASE_FCM, MSG91, TWILIO, META_CLOUD_API, SENDGRID, AWS_SES |
| fallback_provider | VARCHAR(20) | Nullable |
| secrets_manager_key | VARCHAR(200) | AWS Secrets Manager key for credentials |
| daily_send_limit | INTEGER | |
| daily_sent_count | INTEGER | Reset at midnight IST |
| current_status | VARCHAR(10) | HEALTHY, DEGRADED, DOWN |
| last_health_check_at | TIMESTAMPTZ | |
| updated_by | UUID | FK ? admin_users |
| updated_at | TIMESTAMPTZ | |

### communication_cost_daily

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| date | DATE | |
| channel | VARCHAR(10) | |
| provider | VARCHAR(20) | |
| sent_count | INTEGER | |
| delivered_count | INTEGER | |
| fallback_sent_count | INTEGER | |
| cost_rs | DECIMAL(10,2) | |
| created_at | TIMESTAMPTZ | |

### communication_config_audit

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| channel | VARCHAR(10) | |
| changed_by | UUID | FK ? admin_users |
| changed_fields | JSONB | Diff of changed fields (credentials masked) |
| connectivity_test_result | VARCHAR(10) | PASSED, FAILED, SKIPPED |
| changed_at | TIMESTAMPTZ | |

## Acceptance Criteria

1. **AC-001**: GET /status returns `status: DOWN` for a channel whose last health check ping failed; `overall_status: DEGRADED` if any channel is not HEALTHY.
2. **AC-002**: PATCH /config with new API credentials that fail the connectivity test returns `422 CONNECTIVITY_TEST_FAILED` and retains old credentials.
3. **AC-003**: PATCH /config never returns full API credentials in the response; `api_key_preview` shows only first 4 characters + `****`.
4. **AC-004**: GET /usage returns `cost_today_rs` computed from actual message count - per-message rate for each channel.
5. **AC-005**: POST /test sends a real message labelled `is_test: true` in the log; test messages count towards daily_sent_count.
6. **AC-006**: When MSG91 `status: DOWN`, the platform automatically routes SMS through Twilio (`fallback_sent_today` increments in usage stats).
7. **AC-007**: When a channel's `daily_limit_pct_used` reaches 80%, an admin alert fires (EPIC-020 alert type: CHANNEL_LIMIT_WARNING).
8. **AC-008**: Every configuration change creates an audit log entry in `communication_config_audit` with changed fields (credentials masked).

## Dependencies

| Dependency | Type | Notes |
|-----------|------|-------|
| EPIC-017-STORY-001 | Integration point | Push (Firebase FCM) |
| EPIC-017-STORY-002 | Integration point | SMS (MSG91/Twilio) |
| EPIC-017-STORY-003 | Integration point | WhatsApp (Meta) |
| EPIC-017-STORY-004 | Integration point | Email (SendGrid/SES) |
| AWS Secrets Manager | Credential store | Channel API keys |
| EPIC-020 Observability | Alert consumer | Channel health alerts |

## Notes

- This story is the "control plane" for communication channels. The actual message delivery is handled by EPIC-017 stories. This story manages the configuration, health monitoring, and cost tracking layer.
- The health check mechanism sends a lightweight API status call to each provider every 5 minutes (not a full test message send, to avoid cost). Only POST /test sends a real message.
- Future: Add per-channel budget alerts (email/push to admin when monthly spend exceeds a configured threshold), making cost management proactive.
