# STORY-001: Payment Processing (UPI / Card / COD)

| Field | Value |
|---|---|
| Story ID | EPIC-012/STORY-001 |
| Epic | EPIC-012 - Payments and Finance |
| Title | Payment Processing (UPI / Card / COD) |
| Status | Draft |
| Priority | P0 |
| Estimated Effort | 2 Sprints |
| Last Updated | 2026-07-24 |

---

## Overview

This story covers the full payment lifecycle for customer orders on Namma MedMate. When a customer places an order with UPI or card, the backend creates a Cashfree order before checkout confirmation and returns the necessary credentials for the Cashfree JS SDK. After the customer completes payment on their device, the server verifies the HMAC-SHA256 signature to confirm authenticity. A Cashfree webhook handler receives all payment events asynchronously and updates order and payment records accordingly. COD orders bypass the gateway entirely, with `payment_status = PENDING_COD`. Wallet balances are applied first in hybrid scenarios, with the remainder charged through the gateway.

---

## User Roles

| Role | Capability |
|---|---|
| `customer` | Initiate payment, verify payment, view payment detail |
| `admin_finance` | View payment records, monitor webhook events |
| `admin_super` | All admin_finance capabilities |
| System (webhook) | Handle Cashfree webhook events |

---

## Business Rules

| # | Rule |
|---|---|
| BR-001 | A Cashfree order is created **before** checkout confirmation; the customer proceeds to pay only after `cashfree_order_id` is returned. There is exactly one Cashfree order per MedMate order. |
| BR-002 | Payment signature is verified server-side using HMAC-SHA256: `expected_sig = HMAC_SHA256(cashfree_order_id + "|" + cashfree_payment_id, cashfree_secret_key)`. A mismatched signature returns `PAYMENT_SIGNATURE_INVALID` and the order remains in `PAYMENT_PENDING`. |
| BR-003 | COD orders skip the payment gateway entirely; their `payment_status` is set to `PENDING_COD` at order creation and resolved to `COLLECTED_COD` after delivery and COD reconciliation. |
| BR-004 | If the customer has a Namma Money wallet balance, it is automatically applied first at checkout; the remaining amount is charged via the gateway. The wallet debit and Cashfree order creation happen in the same checkout transaction. |
| BR-005 | If a payment fails, the order moves to `PAYMENT_FAILED` state; the customer must retry manually. No automatic gateway retry is supported in v1. |
| BR-006 | The Cashfree webhook handler must be idempotent; if the same webhook event is received twice (duplicate delivery), the second processing is a no-op. Events are deduplicated by `cashfree_payment_id`. |
| BR-007 | Webhook signature verification uses the Cashfree webhook secret: `HMAC_SHA256(webhook_body, webhook_secret)`. Requests failing signature verification are rejected with HTTP 400 and logged. |
| BR-008 | Payment amounts are always in **paise** (integer) in Cashfree API calls; the MedMate system stores amounts in rupees (DECIMAL) and converts to paise only for gateway calls. |

---

## API Endpoints

### POST /api/v1/payments/initiate

**Auth:** `Bearer JWT` (customer)  
**Description:** Create a Cashfree payment order before checkout. Returns `cashfree_order_id` and `payment_session_id` for the Cashfree JS SDK.

**Request Body:**
```json
{
  "order_id": "order_uuid",
  "amount_paise": 49500,
  "currency": "INR",
  "method": "UPI"
}
```

**Response 201 Created:**
```json
{
  "success": true,
  "data": {
    "payment_id": "payment_uuid",
    "order_id": "order_uuid",
    "cashfree_order_id": "order_XXXXXXXXXXXX",
    "payment_session_id": "session_XXXXXXXXXXXX",
    "amount_paise": 49500,
    "amount_rupees": 495.00,
    "currency": "INR",
    "method": "UPI",
    "wallet_deducted": 50.00,
    "gateway_amount_paise": 44500,
    "expires_at": "2026-07-24T13:20:00Z"
  },
  "meta": {}
}
```

**Errors:**
| Code | HTTP | Meaning |
|---|---|---|
| `ORDER_NOT_FOUND` | 404 | order_id does not exist |
| `ORDER_NOT_YOURS` | 403 | Order belongs to different customer |
| `PAYMENT_ALREADY_INITIATED` | 409 | Cashfree order already created for this MedMate order |
| `COD_ORDER_NO_PAYMENT` | 422 | COD orders do not use payment gateway |
| `INVALID_AMOUNT` | 422 | amount_paise does not match order total |
| `CASHFREE_ERROR` | 502 | Cashfree API error on order creation |

---

### POST /api/v1/payments/verify

**Auth:** `Bearer JWT` (customer)  
**Description:** Verify payment after customer completes Cashfree checkout on their device. Validates HMAC-SHA256 signature server-side.

**Request Body:**
```json
{
  "cashfree_payment_id": "pay_XXXXXXXXXXXX",
  "cashfree_order_id": "order_XXXXXXXXXXXX",
  "cashfree_signature": "computed_hmac_sha256_hex_string"
}
```

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "payment_id": "payment_uuid",
    "order_id": "order_uuid",
    "payment_status": "CAPTURED",
    "amount": 495.00,
    "method": "UPI",
    "transaction_id": "pay_XXXXXXXXXXXX",
    "captured_at": "2026-07-24T13:15:00Z"
  },
  "meta": {}
}
```

**Errors:**
| Code | HTTP | Meaning |
|---|---|---|
| `PAYMENT_SIGNATURE_INVALID` | 422 | HMAC-SHA256 signature mismatch |
| `PAYMENT_NOT_FOUND` | 404 | cashfree_order_id does not match any payment |
| `PAYMENT_ALREADY_VERIFIED` | 409 | Payment already in CAPTURED state |

---

### POST /api/v1/payments/webhook/cashfree

**Auth:** Cashfree webhook secret (header: `x-webhook-signature`)  
**Description:** Cashfree webhook handler. Validates signature, processes supported events.

**Supported Events:** `payment.captured`, `payment.failed`, `refund.processed`

**Request Body (Cashfree webhook payload):**
```json
{
  "entity": "event",
  "event": "payment.captured",
  "payload": {
    "payment": {
      "entity": {
        "id": "pay_XXXXXXXXXXXX",
        "order_id": "order_XXXXXXXXXXXX",
        "amount": 49500,
        "currency": "INR",
        "status": "captured",
        "method": "upi",
        "captured": true
      }
    }
  }
}
```

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "event": "payment.captured",
    "payment_id": "pay_XXXXXXXXXXXX",
    "processed": true
  },
  "meta": {}
}
```

**Errors:**
| Code | HTTP | Meaning |
|---|---|---|
| `WEBHOOK_SIGNATURE_INVALID` | 400 | Signature in x-webhook-signature header is invalid |
| `UNKNOWN_EVENT` | 200 | Event type not handled; acknowledged but no action taken |

---

### GET /api/v1/payments/:payment_id

**Auth:** `Bearer JWT` (customer, admin_finance, admin_super)  
**Description:** Fetch payment detail. Customer can only fetch their own payments.

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "payment_id": "payment_uuid",
    "order_id": "order_uuid",
    "customer_id": "customer_uuid",
    "amount": 495.00,
    "wallet_portion": 50.00,
    "gateway_portion": 445.00,
    "method": "UPI",
    "status": "CAPTURED",
    "cashfree_payment_id": "pay_XXXXXXXXXXXX",
    "cashfree_order_id": "order_XXXXXXXXXXXX",
    "gateway_fee": 8.91,
    "captured_at": "2026-07-24T13:15:00Z",
    "created_at": "2026-07-24T13:10:00Z"
  },
  "meta": {}
}
```

**Errors:**
| Code | HTTP | Meaning |
|---|---|---|
| `PAYMENT_NOT_FOUND` | 404 | payment_id does not exist |
| `FORBIDDEN` | 403 | Customer requesting another customer's payment |

---

## Data Models

### Payment

| Field | Type | Nullable | Description |
|---|---|---|---|
| `id` | UUID v4 | No | Primary key |
| `order_id` | UUID | No | FK ? Order; unique |
| `customer_id` | UUID | No | FK ? Customer |
| `amount` | DECIMAL(12,2) | No | Total order amount in Rs |
| `wallet_portion` | DECIMAL(12,2) | No | Amount deducted from wallet (0 if none) |
| `gateway_portion` | DECIMAL(12,2) | No | Amount charged via Cashfree |
| `currency` | CHAR(3) | No | Always `INR` |
| `method` | ENUM(`UPI`,`CARD`,`COD`,`WALLET_ONLY`) | No | Payment method |
| `status` | ENUM(`PENDING`,`CAPTURED`,`FAILED`,`REFUNDED`,`PENDING_COD`,`COLLECTED_COD`) | No | Payment lifecycle state |
| `cashfree_order_id` | VARCHAR(100) | Yes | Cashfree order ID (null for COD/wallet-only) |
| `cashfree_payment_id` | VARCHAR(100) | Yes | Cashfree payment ID after capture |
| `cashfree_signature` | VARCHAR(255) | Yes | HMAC signature verified |
| `gateway_fee` | DECIMAL(10,2) | Yes | Cashfree fee charged |
| `gateway_response` | JSONB | Yes | Raw Cashfree response for audit |
| `webhook_events` | JSONB | Yes | Array of webhook events received |
| `captured_at` | TIMESTAMPTZ | Yes | Payment capture timestamp |
| `failed_at` | TIMESTAMPTZ | Yes | Payment failure timestamp |
| `failure_reason` | TEXT | Yes | Gateway failure reason |
| `idempotency_key` | VARCHAR(100) | Yes | Cashfree idempotency key used |
| `created_at` | TIMESTAMPTZ | No | Record creation timestamp |
| `updated_at` | TIMESTAMPTZ | No | Last update timestamp |

---

## Acceptance Criteria

| # | Criterion |
|---|---|
| AC-001 | `POST /payments/initiate` creates a Cashfree order and returns `cashfree_order_id` and `payment_session_id`; the MedMate payment record is created in `PENDING` state. |
| AC-002 | `POST /payments/verify` with a valid HMAC-SHA256 signature sets payment status to `CAPTURED` and advances the order to the next workflow state. |
| AC-003 | `POST /payments/verify` with an invalid signature returns HTTP 422 `PAYMENT_SIGNATURE_INVALID`; order remains in `PAYMENT_PENDING`. |
| AC-004 | Receiving a `payment.captured` webhook for an already-CAPTURED payment is a no-op (idempotent); returns HTTP 200 without re-processing. |
| AC-005 | Receiving a webhook with an invalid `x-webhook-signature` header returns HTTP 400 `WEBHOOK_SIGNATURE_INVALID`. |
| AC-006 | A COD order creation sets `payment_status = PENDING_COD` without calling the Cashfree API; attempting `POST /payments/initiate` for a COD order returns `COD_ORDER_NO_PAYMENT`. |
| AC-007 | When a customer has Rs 50 wallet balance and places a Rs 495 order, `POST /payments/initiate` shows `wallet_deducted = 50.00` and `gateway_amount_paise = 44500`. |
| AC-008 | `GET /payments/:id` for a payment belonging to a different customer returns HTTP 403 `FORBIDDEN`. |

---

## Dependencies

| Dependency | Type | Notes |
|---|---|---|
| Cashfree Payment Gateway | External | Order creation, signature verification |
| Cashfree Webhook | External | Async payment event delivery |
| Namma Money Wallet (EPIC-012/STORY-002) | Internal | Wallet debit at checkout (before gateway) |
| Order Management (EPIC-010) | Internal | Order status updates on payment capture/failure |
| Financial Ledger (EPIC-012/STORY-008) | Internal | Ledger entry created on payment capture |
| Notification Service (EPIC-013) | Internal | Payment confirmation push/SMS to customer |

---

## Notes

- The Cashfree `key_secret` is stored in an environment variable (`CASHFREE_SECRET_KEY`) and never logged or returned in API responses.
- Webhook endpoint URL must be registered in the Cashfree dashboard; the URL should be `POST https://api.nammamedmate.com/api/v1/payments/webhook/cashfree`.
- `gateway_fee` is captured from the Cashfree payment entity's `fee` field (in paise, converted to Rs on storage) for financial reconciliation.
- For UPI payments, Cashfree handles the VPA flow client-side via the Cashfree SDK; the backend does not interact with UPI directly.
