# STORY-004: Saved Payment Methods

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-004 |
| **Epic** | EPIC-002 - Customer Management |
| **Priority** | P1 |
| **Complexity** | M |
| **Status** | Draft |

---

## Overview

This story allows customers to save up to 5 UPI IDs and up to 5 card payment methods to their account for frictionless checkout. Raw card data is never stored on the Namma MedMate platform - cards are handled entirely via Cashfree's tokenisation API and only the Cashfree token ID plus display metadata (last 4 digits, network, type) are persisted. UPI VPAs (Virtual Payment Addresses) are validated against Cashfree's VPA verification API before saving to prevent invalid entries. COD is always available at checkout as a non-saved method. The story also covers setting a default payment method and safe deletion with order-activity guards.

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| customer | Admin | Full CRUD over own saved payment methods |
| admin_support | Read | Can view a masked list of saved payment methods for support |
| admin_finance | Read | Can view payment method metadata for financial investigation |

## Business Rules

1. A maximum of 5 UPI IDs and 5 cards may be saved per customer. Attempting to exceed either limit returns `422 PAYMENT_METHOD_LIMIT_REACHED` with a message specifying which type is at capacity.
2. Card data (full card number, CVV, expiry) is NEVER transmitted to or stored on Namma MedMate servers. The client app integrates with Cashfree's SDK to tokenise the card; only the resulting `cashfree_token_id`, masked `card_last4`, `card_network` (VISA, MASTERCARD, RUPAY, AMEX), and `card_type` (CREDIT, DEBIT, PREPAID) are sent to the API.
3. A UPI VPA must be validated via Cashfree's `GET /v1/payments/validate/vpa` API before the address is saved. If the VPA is invalid (returns `success: false` from Cashfree), the request is rejected with `422 INVALID_UPI_VPA`. This validation call must succeed within 5 seconds; timeout returns `503 VPA_VALIDATION_TIMEOUT`.
4. A payment method that is actively being used in an in-flight order (order status `PENDING`, `CONFIRMED`, `PACKED`, `OUT_FOR_DELIVERY`) cannot be deleted. Attempting deletion returns `409 PAYMENT_METHOD_IN_ACTIVE_ORDER`.
5. There can only be one default payment method at a time. Setting a new default via `PATCH /:id/set-default` atomically unsets `is_default` on the previous default.
6. COD (Cash on Delivery) is always available as a checkout option and is not represented as a saved payment method in this model. It appears as a dynamically injected option at checkout based on the platform config `cod_available`.
7. The Cashfree token ID is the only reference stored for cards; if a token becomes invalid (card expired, bank blocked it), the checkout flow handles the failure and prompts the customer to remove and re-add the card. There is no server-side periodic token validation.
8. Saved payment method metadata displayed to customers must always be masked: UPI IDs show only the handle portion (e.g., `***@okaxis`), cards show only `last4` and `network`. Full UPI IDs are stored server-side but never returned to the client in full.

## API Endpoints

### 1. List Saved Payment Methods

```
GET /api/v1/customers/me/payment-methods
```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 30 req/min per user

**Success Response - `200 OK`:**
```json
{
  "success": true,
  "data": {
    "upi": [
      {
        "id": "pm-upi-uuid-1",
        "type": "UPI",
        "nickname": "GPay",
        "upi_handle": "***@okicici",
        "is_default": true,
        "added_at": "2026-03-01T10:00:00Z"
      }
    ],
    "cards": [
      {
        "id": "pm-card-uuid-1",
        "type": "CARD",
        "card_last4": "4242",
        "card_network": "VISA",
        "card_type": "CREDIT",
        "nickname": "Axis Flipkart Card",
        "is_default": false,
        "added_at": "2026-04-15T08:00:00Z"
      }
    ]
  },
  "meta": {}
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 401 | `UNAUTHORIZED` | Token missing or invalid |

---

### 2. Save UPI ID

```
POST /api/v1/customers/me/payment-methods/upi
```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 10 req/min per user

**Request Body (`application/json`):**
```json
{
  "upi_id": "string - required, full UPI VPA (e.g. ramesh@okaxis), max:100, validated against Cashfree",
  "nickname": "string - optional, max:50, e.g. GPay, PhonePe"
}
```

**Success Response - `201 Created`:**
```json
{
  "success": true,
  "data": {
    "id": "pm-upi-uuid-2",
    "type": "UPI",
    "nickname": "PhonePe",
    "upi_handle": "***@ybl",
    "is_default": false,
    "added_at": "2026-07-24T02:00:00Z"
  },
  "meta": {}
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `VALIDATION_ERROR` | Invalid UPI ID format |
| 401 | `UNAUTHORIZED` | Token missing or invalid |
| 409 | `UPI_ALREADY_SAVED` | This UPI ID is already in the customer's saved methods |
| 422 | `INVALID_UPI_VPA` | Cashfree VPA validation returned invalid |
| 422 | `PAYMENT_METHOD_LIMIT_REACHED` | 5 UPI IDs already saved |
| 503 | `VPA_VALIDATION_TIMEOUT` | Cashfree VPA validation timed out |

---

### 3. Save Card (via Cashfree Token)

```
POST /api/v1/customers/me/payment-methods/card
```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 10 req/min per user

**Request Body (`application/json`):**
```json
{
  "cashfree_token_id": "string - required, Cashfree card token ID (e.g. token_xxxxx), max:100",
  "card_last4": "string - required, last 4 digits of the card",
  "card_network": "string - required, enum: VISA|MASTERCARD|RUPAY|AMEX|MAESTRO|DINERS",
  "card_type": "string - required, enum: CREDIT|DEBIT|PREPAID",
  "nickname": "string - optional, max:50, e.g. Axis Flipkart"
}
```

**Success Response - `201 Created`:**
```json
{
  "success": true,
  "data": {
    "id": "pm-card-uuid-2",
    "type": "CARD",
    "card_last4": "1234",
    "card_network": "MASTERCARD",
    "card_type": "DEBIT",
    "nickname": "SBI Debit",
    "is_default": false,
    "added_at": "2026-07-24T02:00:00Z"
  },
  "meta": {}
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `VALIDATION_ERROR` | Missing fields or invalid enum value |
| 401 | `UNAUTHORIZED` | Token missing or invalid |
| 422 | `INVALID_CASHFREE_TOKEN` | Token ID format is unrecognised |
| 422 | `PAYMENT_METHOD_LIMIT_REACHED` | 5 cards already saved |

---

### 4. Delete Payment Method

```
DELETE /api/v1/customers/me/payment-methods/:id
```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 10 req/min per user

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| :id | UUID | Payment method ID to delete |

**Success Response - `200 OK`:**
```json
{
  "success": true,
  "data": {
    "message": "Payment method removed successfully."
  },
  "meta": {}
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 401 | `UNAUTHORIZED` | Token missing or invalid |
| 404 | `PAYMENT_METHOD_NOT_FOUND` | Method not found or does not belong to this customer |
| 409 | `PAYMENT_METHOD_IN_ACTIVE_ORDER` | Method is the payment source for an in-flight order |

---

### 5. Set Default Payment Method

```
PATCH /api/v1/customers/me/payment-methods/:id/set-default
```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 10 req/min per user

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| :id | UUID | Payment method ID to set as default |

**Success Response - `200 OK`:**
```json
{
  "success": true,
  "data": {
    "id": "pm-card-uuid-1",
    "type": "CARD",
    "is_default": true,
    "previous_default_id": "pm-upi-uuid-1",
    "message": "Default payment method updated."
  },
  "meta": {}
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 401 | `UNAUTHORIZED` | Token missing or invalid |
| 404 | `PAYMENT_METHOD_NOT_FOUND` | Method not found or does not belong to this customer |
| 409 | `ALREADY_DEFAULT` | This method is already the default |

---

## Data Models

### SavedPaymentMethod

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | UUID | PK, auto-gen | Primary key |
| customer_id | UUID | FK ? customers.id, NOT NULL, indexed | Owning customer |
| type | VARCHAR(10) | NOT NULL | UPI \| CARD |
| is_default | BOOLEAN | NOT NULL, default false | Whether this is the default method |
| nickname | VARCHAR(50) | nullable | Customer-assigned friendly name |
| upi_id | VARCHAR(100) | nullable | Full UPI VPA; stored encrypted; only for type=UPI |
| upi_handle | VARCHAR(100) | nullable | Masked display string (e.g., `***@okaxis`) |
| cashfree_token_id | VARCHAR(100) | nullable | Cashfree token; only for type=CARD |
| card_last4 | CHAR(4) | nullable | Last 4 card digits; only for type=CARD |
| card_network | VARCHAR(15) | nullable | VISA \| MASTERCARD \| RUPAY \| AMEX \| MAESTRO \| DINERS |
| card_type | VARCHAR(10) | nullable | CREDIT \| DEBIT \| PREPAID |
| created_at | TIMESTAMPTZ | NOT NULL, default NOW() | When the method was saved |
| deleted_at | TIMESTAMPTZ | nullable | Soft delete |

## Acceptance Criteria

- [ ] Given a valid UPI ID, when `POST /customers/me/payment-methods/upi` is called, then Cashfree VPA validation is triggered; if the VPA is valid, a new saved method is created and the stored `upi_id` is encrypted at rest while only the masked `upi_handle` is returned in the response.
- [ ] Given a customer with 5 saved UPI IDs, when `POST /customers/me/payment-methods/upi` is called with a 6th UPI ID, then `422 PAYMENT_METHOD_LIMIT_REACHED` is returned.
- [ ] Given `POST /customers/me/payment-methods/card` is called with a `cashfree_token_id` but no `card_last4`, then `400 VALIDATION_ERROR` is returned with a message indicating `card_last4` is required.
- [ ] Given a saved card is the payment source for an order in `CONFIRMED` status, when `DELETE /customers/me/payment-methods/:id` is called, then `409 PAYMENT_METHOD_IN_ACTIVE_ORDER` is returned.
- [ ] Given two saved methods A (default) and B (not default), when `PATCH /payment-methods/B/set-default` is called, then B has `is_default: true` and A has `is_default: false` in the same atomic DB transaction.
- [ ] Given `GET /customers/me/payment-methods`, then no full UPI IDs or card numbers are present in the response - only masked `upi_handle` and `card_last4` are visible.
- [ ] Given a card payment method with type=CARD, the `cashfree_token_id` field must never appear in any customer-facing API response (it is internal only).

## Dependencies

- EPIC-001 / STORY-001 - Customer must be authenticated
- EPIC-003 / STORY-002 - Checkout uses saved payment methods
- EPIC-008 - Cashfree integration for UPI VPA validation and card tokenisation

## Notes

- UPI IDs must be stored encrypted at rest (AES-256-GCM). The masking logic for `upi_handle` should preserve the `@provider` suffix and replace the local part with `***`.
- `cashfree_token_id` should also be encrypted at rest as it is a sensitive payment token.
- COD availability is determined at checkout time from the platform config, not from the saved payment methods model. Never create a saved method record for COD.
- Future iteration: support NetBanking saved bank account (similar token pattern via Cashfree).
