# STORY-001: Razorpay Integration

| Field | Value |
|-------|-------|
| Story ID | EPIC-022-STORY-001 |
| Epic | EPIC-022 External Integrations |
| Title | Razorpay Integration |
| Priority | P1 |
| Status | In Development |
| Role | Internal service + admin_finance |
| Last Updated | 2026-07-24 |

## Overview

The Razorpay Integration story covers the full payment collection and payout disbursement cycle using Razorpay (for customer payment orders) and RazorpayX (for pharmacy and rider bank account payouts). It implements Razorpay order creation for payment initiation, webhook handling for payment lifecycle events, UPI VPA verification for customer payment method validation, RazorpayX fund account management for beneficiaries, and RazorpayX payout initiation for settlements. All amounts use paise (smallest unit).

## User Roles

| Role | Access |
|------|--------|
| Internal services | Call payment and payout endpoints (service-to-service) |
| admin_finance | View payout logs; verify fund accounts |

## Business Rules

1. **Paise Convention**: All monetary values in API requests and responses are in paise (1 Rs = 100 paise). Displaying amounts in Rs is a frontend concern.
2. **Webhook Signature Verification**: Every Razorpay webhook received at POST /webhook must be verified using HMAC-SHA256 with the Razorpay webhook secret. Signature is in the `X-Razorpay-Signature` header. Unverified webhooks return 400 and are not processed.
3. **Webhook Idempotency**: Razorpay may send duplicate webhook events. The platform uses the `razorpay_payment_id` (for payment events) and `razorpay_payout_id` (for payout events) as idempotency keys. If a payment_id or payout_id is already in the database with the processed event, the duplicate is ignored.
4. **Payout Mode Selection**: IMPS for payouts ? Rs 2,00,000 (same-day settlement, works 24x7). NEFT for payouts > Rs 2,00,000 (next business day, lower cost). Mode is auto-selected unless overridden.
5. **Fund Account Reuse**: A fund account (bank account for payout) is created once per pharmacy or rider. Future payouts reuse the existing fund account. If bank details change, a new fund account is created (old one is deactivated in RazorpayX).
6. **Failed Payout Retry**: A payout that fails (bank returns error) is retried once automatically after 1 hour. After two failures, the payout is flagged for manual review in Admin Finance and the entity is notified.
7. **Test vs Live Mode**: The platform uses a config flag `razorpay_mode: TEST|LIVE`. In TEST mode, all Razorpay API calls go to the Razorpay test environment. Live mode should only be enabled after UAT sign-off.
8. **Payment Capture**: Payment authorization alone is not sufficient - the platform must explicitly capture authorized payments. Capture happens in the `payment.authorized` webhook handler. Uncaptured authorized payments expire after 5 days.
9. **Refund Handling**: Refunds are initiated via Razorpay's refund API. The `refund.processed` webhook updates the finance ledger. Partial refunds are supported.
10. **UPI VPA Verification**: Before saving a UPI VPA as a payment method, the platform verifies it via the Razorpay UPI VPA validation API. Invalid VPAs are rejected at the payment method save step.

## API Endpoints

### POST /api/v1/integrations/razorpay/create-order

Create a Razorpay order for payment collection.

**Auth**: Service-to-service JWT (internal only)

**Request Body**
```json
{
  "amount_paise": 50400,
  "currency": "INR",
  "receipt": "ORD-8821",
  "notes": {
    "platform_order_id": "uuid-order-1",
    "customer_id": "uuid-customer-1",
    "pharmacy_id": "uuid-ph-1"
  }
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "razorpay_order_id": "order_abc123",
    "amount_paise": 50400,
    "currency": "INR",
    "receipt": "ORD-8821",
    "status": "created",
    "created_at": "2026-07-24T10:10:00Z"
  },
  "meta": {}
}
```

**Error Table**

| HTTP Code | Error Code | Condition |
|-----------|-----------|-----------|
| 400 | AMOUNT_TOO_SMALL | amount_paise < 100 (Rs 1 minimum) |
| 503 | RAZORPAY_UNAVAILABLE | Razorpay API returned error |

---

### POST /api/v1/integrations/razorpay/webhook

Handle Razorpay webhook events.

**Auth**: Public (Razorpay IP whitelist + signature verification)

**Headers Required**: `X-Razorpay-Signature`

**Request Body** (example: payment.captured)
```json
{
  "entity": "event",
  "event": "payment.captured",
  "payload": {
    "payment": {
      "entity": {
        "id": "pay_xyz789",
        "order_id": "order_abc123",
        "amount": 50400,
        "currency": "INR",
        "status": "captured",
        "method": "upi",
        "captured": true,
        "created_at": 1721808028
      }
    }
  }
}
```

**Handled Events**:
- `payment.authorized` ? Capture payment, update order status to PAYMENT_AUTHORIZED
- `payment.captured` ? Update finance ledger, mark order as PAYMENT_CAPTURED
- `payment.failed` ? Update order status, notify customer, release stock
- `refund.created` ? Create refund record in ledger
- `refund.processed` ? Update refund status, notify customer
- `payout.processed` ? Update payout record in finance ledger
- `payout.failed` ? Trigger retry flow, notify pharmacy/rider

**Response 200**
```json
{ "success": true }
```

**Error Table**

| HTTP Code | Error Code | Condition |
|-----------|-----------|-----------|
| 400 | INVALID_SIGNATURE | X-Razorpay-Signature verification failed |

---

### POST /api/v1/integrations/razorpayx/payout

Initiate a payout to pharmacy or rider bank account via RazorpayX.

**Auth**: Service-to-service JWT (internal only)

**Request Body**
```json
{
  "fund_account_id": "fa_abc456",
  "amount_paise": 4200000,
  "mode": "IMPS",
  "purpose": "payout",
  "reference_id": "PAYOUT-2026-07-15-001",
  "notes": {
    "entity_type": "PHARMACY",
    "entity_id": "uuid-ph-1",
    "settlement_period": "2026-07-01 to 2026-07-15"
  }
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "razorpayx_payout_id": "pout_def789",
    "fund_account_id": "fa_abc456",
    "amount_paise": 4200000,
    "mode": "IMPS",
    "status": "processing",
    "reference_id": "PAYOUT-2026-07-15-001",
    "initiated_at": "2026-07-24T10:15:00Z"
  },
  "meta": {}
}
```

**Error Table**

| HTTP Code | Error Code | Condition |
|-----------|-----------|-----------|
| 422 | FUND_ACCOUNT_NOT_FOUND | fund_account_id not found in RazorpayX |
| 422 | INSUFFICIENT_BALANCE | RazorpayX account balance insufficient |
| 503 | RAZORPAYX_UNAVAILABLE | RazorpayX API error |

---

### POST /api/v1/integrations/razorpay/verify-upi

Verify a UPI Virtual Payment Address (VPA).

**Auth**: Service-to-service JWT (internal only)

**Request Body**
```json
{
  "vpa": "ravi.kumar@okicici"
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "vpa": "ravi.kumar@okicici",
    "valid": true,
    "name": "RAVI KUMAR"
  },
  "meta": {}
}
```

---

### POST /api/v1/integrations/razorpay/fund-account

Create or update a fund account for payout beneficiary.

**Auth**: Service-to-service JWT (internal only)

**Request Body**
```json
{
  "entity_type": "PHARMACY",
  "entity_id": "uuid-ph-1",
  "bank_name": "HDFC Bank",
  "account_number": "50100123456789",
  "ifsc": "HDFC0001234",
  "account_holder_name": "Apollo Pharmacy India Ltd"
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "fund_account_id": "fa_abc456",
    "entity_type": "PHARMACY",
    "entity_id": "uuid-ph-1",
    "bank_name": "HDFC Bank",
    "account_last4": "6789",
    "ifsc": "HDFC0001234",
    "account_holder_name": "Apollo Pharmacy India Ltd",
    "razorpayx_contact_id": "cont_ghi012",
    "created_at": "2026-07-24T10:12:00Z"
  },
  "meta": {}
}
```

**Error Table**

| HTTP Code | Error Code | Condition |
|-----------|-----------|-----------|
| 422 | INVALID_IFSC | IFSC code format invalid |
| 422 | INVALID_ACCOUNT_NUMBER | Account number fails basic validation |
| 503 | RAZORPAYX_UNAVAILABLE | RazorpayX API error |

---

## Data Models

### razorpay_payment_records

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| platform_order_id | UUID | FK ? orders |
| razorpay_order_id | VARCHAR(50) | |
| razorpay_payment_id | VARCHAR(50) | Unique; set on capture |
| amount_paise | INTEGER | |
| currency | VARCHAR(3) | INR |
| payment_method | VARCHAR(20) | upi, card, netbanking, cod |
| status | VARCHAR(20) | created, authorized, captured, failed, refunded |
| created_at | TIMESTAMPTZ | |
| captured_at | TIMESTAMPTZ | Nullable |

### razorpayx_fund_accounts

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| entity_type | VARCHAR(10) | PHARMACY, RIDER |
| entity_id | UUID | |
| razorpayx_contact_id | VARCHAR(50) | RazorpayX contact ID |
| fund_account_id | VARCHAR(50) | RazorpayX fund account ID |
| bank_name | VARCHAR(100) | |
| account_last4 | VARCHAR(4) | Last 4 digits of account |
| ifsc | VARCHAR(12) | |
| account_holder_name | VARCHAR(200) | |
| is_active | BOOLEAN | |
| created_at | TIMESTAMPTZ | |

### razorpayx_payout_records

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| entity_type | VARCHAR(10) | PHARMACY, RIDER |
| entity_id | UUID | |
| fund_account_id | VARCHAR(50) | |
| razorpayx_payout_id | VARCHAR(50) | Unique |
| reference_id | VARCHAR(100) | Platform payout reference |
| amount_paise | BIGINT | |
| mode | VARCHAR(5) | IMPS, NEFT, UPI |
| status | VARCHAR(15) | processing, processed, reversed, failed |
| retry_count | SMALLINT | Default 0 |
| initiated_at | TIMESTAMPTZ | |
| processed_at | TIMESTAMPTZ | Nullable |
| failure_reason | TEXT | Nullable |

## Acceptance Criteria

1. **AC-001**: POST /webhook with invalid `X-Razorpay-Signature` returns `400 INVALID_SIGNATURE` without processing.
2. **AC-002**: Duplicate `payment.captured` webhook (same `razorpay_payment_id`) is silently ignored (returns 200 but no duplicate ledger entry).
3. **AC-003**: POST /razorpayx/payout with `amount_paise ? 20000000` uses mode `IMPS`; amount > 20000000 uses mode `NEFT` (auto-selection).
4. **AC-004**: POST /verify-upi for a valid UPI VPA returns `valid: true` and the registered account holder name.
5. **AC-005**: POST /fund-account for a pharmacy creates a RazorpayX contact + fund account and stores `fund_account_id` for future payouts.
6. **AC-006**: A failed payout automatically retries once after 1 hour; after 2 failures, a manual-review alert is created.
7. **AC-007**: `payment.authorized` webhook triggers explicit capture API call to Razorpay (not auto-capture); order status updates to PAYMENT_CAPTURED on success.
8. **AC-008**: Razorpay test mode (`razorpay_mode: TEST`) routes all API calls to `api.razorpay.com/v1` with test API keys; no live transactions.

## Dependencies

| Dependency | Type | Notes |
|-----------|------|-------|
| Razorpay | External | Payment gateway |
| RazorpayX | External | Payout disbursement |
| EPIC-005 Finance | Consumer | Finance ledger updates |
| EPIC-001 Order Management | Consumer | Order status updates |
| AWS Secrets Manager | Credential store | API keys |

## Notes

- Account numbers are never logged in full. Only `account_last4` is stored. The full account number is used only during fund account creation and never persisted.
- RazorpayX requires a Razorpay "contact" to be created before a fund account. The `razorpayx_contact_id` links the two.
- Indian UPI VPA format: `local@bank` (e.g., `9876543210@paytm`). Razorpay's VPA validation covers all major UPI handles.
