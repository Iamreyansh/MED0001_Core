# STORY-003: Namma Money Wallet

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-003 |
| **Epic** | EPIC-002 - Customer Management |
| **Priority** | P0 |
| **Complexity** | L |
| **Status** | Draft |

---

## Overview

The Namma Money wallet is a closed-loop digital wallet tied to every customer account, used primarily for receiving refunds (both COD and online payment refunds), goodwill credits, and promotional incentives. The wallet balance is automatically applied at checkout to reduce the payable amount. Customers cannot withdraw wallet funds to a bank account - it is strictly for in-platform spending. Admin finance staff can manually credit wallets for exceptional cases (refunds outside automated flows, goodwill gestures). Every credit and debit is fully traceable via the wallet transaction ledger with reason codes and reference IDs.

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| customer | Read | Can view own wallet balance and transaction history |
| admin_super | Admin | Full read/write access; can credit wallet |
| admin_finance | Write | Can credit wallets; view all wallet transactions |
| admin_support | Read | Can view wallet balance and history for customer support |
| admin_operations | Read | Can view wallet summary for operational insights |

## Business Rules

1. Every customer has exactly one wallet, created automatically when their account is first created. The initial balance is Rs 0.00.
2. At checkout, the wallet balance is automatically applied in full (entire balance deducted up to the order amount). The wallet cannot be partially applied - it is either used in full or not used if balance is zero. Customers are shown the expected wallet deduction before confirming checkout.
3. COD order refunds (e.g., cancellations after order acceptance) are credited to the wallet. Online payment refunds can be credited to either the wallet or the original payment source - the refund routing is determined by the refund policy in EPIC-008 (payment story).
4. Wallet funds cannot be withdrawn to any bank account, UPI ID, or card. Attempting this via any API returns `422 WALLET_NOT_WITHDRAWABLE`.
5. Each wallet credit entry has an `expires_at` field set to `created_at + 365 days`. Expired credits are not counted in the `balance` (the balance is always the sum of non-expired, non-debited credits). A background job marks expired credits as `EXPIRED` and recalculates the balance.
6. Admin manual wallet credit is limited to a maximum of Rs 1,000 per transaction (configurable via platform config key `max_wallet_credit_per_transaction`). Attempting to credit more returns `422 EXCEEDS_CREDIT_LIMIT`. The `reason` must be one of: `REFUND`, `GOODWILL`, `PROMOTIONAL`. Only admin users with the `finance:*` permission (admin_finance and admin_super) can manually credit wallets.
7. All wallet credits and debits are recorded in `wallet_transactions` with a `balance_after` snapshot to allow reconstruction of the balance at any point in time. The `reference_id` links the transaction to the originating order, refund, or admin action.
8. The wallet balance can never go negative. A debit that would result in a negative balance is rejected. In practice, the checkout flow caps the wallet deduction at `min(wallet_balance, order_total)`.

## API Endpoints

### 1. Get Wallet Balance

```
GET /api/v1/customers/me/wallet
```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 30 req/min per user

**Success Response - `200 OK`:**
```json
{
  "success": true,
  "data": {
    "wallet_id": "w1a2b3c4-d5e6-7890-fghi-j12345678901",
    "balance": 125.50,
    "lifetime_credited": 600.00,
    "lifetime_debited": 474.50,
    "expiring_soon": {
      "amount": 50.00,
      "expires_before": "2026-08-24T00:00:00Z"
    },
    "currency": "INR"
  },
  "meta": {}
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 401 | `UNAUTHORIZED` | Token missing or invalid |

---

### 2. Get Wallet Transaction History

```
GET /api/v1/customers/me/wallet/transactions
```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 20 req/min per user

**Query Parameters:**
| Param | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| page | integer | No | 1 | Page number |
| limit | integer | No | 20 | Results per page, max 100 |
| sort | string | No | created_at | Sort field |
| order | string | No | desc | asc \| desc |
| type | string | No | - | Filter: CREDIT \| DEBIT \| EXPIRED |

**Success Response - `200 OK`:**
```json
{
  "success": true,
  "data": [
    {
      "id": "t1a2b3c4-d5e6-7890-fghi-j12345678901",
      "type": "CREDIT",
      "amount": 50.00,
      "balance_after": 125.50,
      "reason": "REFUND",
      "description": "Refund for cancelled order #ORD-20260720-00123",
      "reference_id": "ord-uuid-here",
      "expires_at": "2027-07-20T00:00:00Z",
      "created_at": "2026-07-20T16:00:00Z"
    },
    {
      "id": "t2a2b3c4-d5e6-7890-fghi-j12345678902",
      "type": "DEBIT",
      "amount": 75.50,
      "balance_after": 75.50,
      "reason": "ORDER_PAYMENT",
      "description": "Payment for order #ORD-20260718-00089",
      "reference_id": "ord-uuid-here",
      "expires_at": null,
      "created_at": "2026-07-18T12:30:00Z"
    }
  ],
  "meta": {
    "page": 1,
    "limit": 20,
    "total": 14,
    "has_next": false
  }
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 401 | `UNAUTHORIZED` | Token missing or invalid |

---

### 3. Admin Manual Wallet Credit

```
POST /api/v1/admin/customers/:id/wallet/credit
```

**Authentication:** Bearer JWT - `admin_super`, `admin_finance`
**Rate Limit:** 20 req/min per user

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| :id | UUID | Customer ID to credit |

**Request Body (`application/json`):**
```json
{
  "amount": "number - required, positive, max: 1000 (configurable), in INR, max 2 decimal places",
  "reason": "string - required, enum: REFUND|GOODWILL|PROMOTIONAL",
  "note": "string - required, max:500, describes why the credit is being issued",
  "reference_id": "string - optional, max:255, ID of related order/ticket/case"
}
```

**Success Response - `201 Created`:**
```json
{
  "success": true,
  "data": {
    "transaction_id": "t3a2b3c4-d5e6-7890-fghi-j12345678903",
    "customer_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "amount_credited": 100.00,
    "new_balance": 225.50,
    "reason": "GOODWILL",
    "expires_at": "2027-07-24T00:00:00Z",
    "credited_by": "admin-staff-id",
    "created_at": "2026-07-24T02:00:00Z"
  },
  "meta": {}
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `VALIDATION_ERROR` | Missing fields, non-positive amount, or invalid reason |
| 401 | `UNAUTHORIZED` | Token missing or invalid |
| 403 | `FORBIDDEN` | Role lacks `finance:*` permission |
| 404 | `CUSTOMER_NOT_FOUND` | Customer with given ID not found |
| 422 | `EXCEEDS_CREDIT_LIMIT` | Amount exceeds `max_wallet_credit_per_transaction` config value |

---

## Data Models

### Wallet

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | UUID | PK, auto-gen | Primary key |
| customer_id | UUID | FK ? customers.id, UNIQUE, NOT NULL | One wallet per customer |
| balance | NUMERIC(12,2) | NOT NULL, default 0.00, CHECK ? 0 | Current spendable balance (non-expired credits minus debits) |
| lifetime_credited | NUMERIC(12,2) | NOT NULL, default 0.00 | Running total of all credits ever issued |
| lifetime_debited | NUMERIC(12,2) | NOT NULL, default 0.00 | Running total of all debits ever recorded |
| created_at | TIMESTAMPTZ | NOT NULL, default NOW() | Wallet creation time |
| updated_at | TIMESTAMPTZ | NOT NULL | Last balance update time |

### WalletTransaction

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | UUID | PK, auto-gen | Primary key |
| wallet_id | UUID | FK ? wallets.id, NOT NULL, indexed | Owning wallet |
| type | VARCHAR(10) | NOT NULL | CREDIT \| DEBIT \| EXPIRED |
| amount | NUMERIC(10,2) | NOT NULL, CHECK > 0 | Transaction amount in INR |
| balance_after | NUMERIC(12,2) | NOT NULL | Balance snapshot after this transaction |
| reason | VARCHAR(30) | NOT NULL | REFUND \| GOODWILL \| PROMOTIONAL \| ORDER_PAYMENT \| EXPIRY |
| description | VARCHAR(500) | nullable | Human-readable description |
| reference_id | VARCHAR(255) | nullable, indexed | ID of linked order, refund, or admin action |
| credited_by | UUID | FK ? admin_staff.id, nullable | Set when an admin manually issued the credit |
| expires_at | TIMESTAMPTZ | nullable | Only set for CREDIT transactions; NULL for DEBIT |
| created_at | TIMESTAMPTZ | NOT NULL, default NOW() | Transaction timestamp (append-only) |

## Acceptance Criteria

- [ ] Given a newly created customer, when `GET /customers/me/wallet` is called, then the response shows `balance: 0.00`, `lifetime_credited: 0.00`, and `lifetime_debited: 0.00`.
- [ ] Given an admin_finance user, when `POST /admin/customers/:id/wallet/credit` is called with `amount: 100, reason: "GOODWILL"`, then a wallet transaction is created with `type: CREDIT`, `balance_after` is the previous balance + 100, and `expires_at` is exactly 365 days from `created_at`.
- [ ] Given `max_wallet_credit_per_transaction` is Rs 1,000, when an admin tries to credit Rs 1,500, then `422 EXCEEDS_CREDIT_LIMIT` is returned.
- [ ] Given an admin_support user (who lacks `finance:*` permission), when `POST /admin/customers/:id/wallet/credit` is called, then `403 FORBIDDEN` is returned.
- [ ] Given a customer with wallet balance Rs 200 and an order total of Rs 350, when checkout is initiated, then the wallet balance of Rs 200 is deducted in full, the remaining Rs 150 is charged via the selected payment method, and a `DEBIT` wallet transaction is created with `balance_after: 0.00`.
- [ ] Given a wallet credit that was issued 366 days ago, when the nightly expiry job runs, then a new `EXPIRED` transaction is inserted for that credit amount and the wallet `balance` is decremented accordingly.
- [ ] Given `GET /customers/me/wallet/transactions?type=CREDIT`, then only transactions with `type: CREDIT` are returned in the paginated response.

## Dependencies

- EPIC-001 / STORY-001 - Wallet created on first customer account creation
- EPIC-003 / STORY-002 - Order payment flow debits the wallet at checkout
- EPIC-008 / STORY-003 - Refund processing credits the wallet
- EPIC-021 / STORY-004 - Platform config `max_wallet_credit_per_transaction`

## Notes

- Wallet balance updates must use database-level locking or optimistic concurrency (a `version` column) to prevent race conditions when multiple concurrent transactions attempt to update the same wallet.
- The `balance` field on the `Wallet` table is a denormalised cache. The authoritative balance at any point can be reconstructed by summing `wallet_transactions`. A consistency check job should periodically verify that `balance` matches the sum.
- Consider using PostgreSQL `SERIALIZABLE` isolation or `SELECT ... FOR UPDATE` on the wallet row during checkout to ensure atomicity.
