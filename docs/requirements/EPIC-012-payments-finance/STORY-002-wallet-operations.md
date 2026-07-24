# STORY-002: Namma Money Wallet Operations

| Field | Value |
|---|---|
| Story ID | EPIC-012/STORY-002 |
| Epic | EPIC-012 - Payments and Finance |
| Title | Namma Money Wallet Operations |
| Status | Draft |
| Priority | P0 |
| Estimated Effort | 2 Sprints |
| Last Updated | 2026-07-24 |

---

## Overview

The Namma Money wallet is an in-app store-of-value for customers. Credits are issued for refunds, cashback, referral rewards, and admin adjustments; debits occur automatically at checkout. The wallet is tightly constrained: it cannot go negative, cannot be withdrawn to a bank account, and credits expire after 365 days on a FIFO basis. All debit operations use idempotency keys to prevent double-charges. Admins can issue manual credits up to Rs 1,000 for goodwill or dispute resolution. Customers see their balance in the cart and transaction history in their profile.

---

## User Roles

| Role | Capability |
|---|---|
| `customer` | View wallet balance, view transaction history |
| `admin_finance` | Credit wallet for refunds, admin adjustments |
| `admin_support` | Credit wallet (goodwill, dispute resolution) |
| `admin_super` | All of the above |
| System (internal) | Debit wallet at checkout, credit wallet on refund |

---

## Business Rules

| # | Rule |
|---|---|
| BR-001 | The wallet balance **cannot go negative**; if the debit amount exceeds the balance, only the available balance is deducted and the remainder is charged via the payment gateway. |
| BR-002 | **Idempotency:** `POST /wallet/debit` is idempotent per `idempotency_key`. A second request with the same key returns the original result without re-processing. |
| BR-003 | **Credit expiry:** wallet credits expire 365 days after issuance. Credits are consumed in FIFO order (oldest first) on each debit. |
| BR-004 | Manual admin credits are capped at **Rs 1,000 per transaction** per customer. |
| BR-005 | **Refund routing:** COD order refunds always go to the wallet (instant). Online payment refunds go to the source account (Razorpay refund API) unless the customer explicitly chooses wallet. Wallet-paid portions of refunds always return to the wallet. |
| BR-006 | The wallet is **not a bank account** - customers cannot withdraw to UPI or bank. The only way credits leave is via checkout debits. |
| BR-007 | All wallet mutations (debit/credit) use **database-level transactions** to ensure atomicity; partial writes are never committed. |
| BR-008 | The `balance_before` and `balance_after` fields are stored on each `WalletTransaction` record for audit - they are recorded at the time of the mutation and are not recomputed from history. |

---

## API Endpoints

### POST /api/v1/wallet/debit

**Auth:** Internal service token (not customer-facing; called by checkout service)  
**Description:** Debit the customer's wallet during checkout. Idempotent per `idempotency_key`.

**Request Body:**
```json
{
  "customer_id": "customer_uuid",
  "amount": 50.00,
  "order_id": "order_uuid",
  "idempotency_key": "checkout_order_uuid_attempt_1"
}
```

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "transaction_id": "txn_uuid",
    "customer_id": "customer_uuid",
    "deducted_amount": 50.00,
    "balance_before": 150.00,
    "remaining_balance": 100.00,
    "idempotency_key": "checkout_order_uuid_attempt_1",
    "already_processed": false
  },
  "meta": {}
}
```

**Errors:**
| Code | HTTP | Meaning |
|---|---|---|
| `CUSTOMER_NOT_FOUND` | 404 | customer_id does not exist |
| `INSUFFICIENT_BALANCE` | 422 | amount > wallet balance (should not occur - checkout caps debit to balance) |
| `INVALID_AMOUNT` | 422 | amount must be > 0 |

---

### POST /api/v1/wallet/credit

**Auth:** Internal service token OR `Bearer JWT` (admin_finance, admin_support, admin_super)  
**Description:** Credit a customer's wallet for refunds, rewards, or admin adjustments.

**Request Body:**
```json
{
  "customer_id": "customer_uuid",
  "amount": 100.00,
  "reason": "REFUND",
  "reference_id": "order_uuid",
  "note": "Refund for cancelled order MED-20260724-015."
}
```

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "transaction_id": "txn_uuid",
    "customer_id": "customer_uuid",
    "amount": 100.00,
    "reason": "REFUND",
    "balance_before": 100.00,
    "new_balance": 200.00,
    "expires_at": "2027-07-24T00:00:00Z",
    "created_at": "2026-07-24T14:00:00Z"
  },
  "meta": {}
}
```

**Errors:**
| Code | HTTP | Meaning |
|---|---|---|
| `CUSTOMER_NOT_FOUND` | 404 | customer_id does not exist |
| `INVALID_REASON` | 422 | reason not in allowed enum |
| `ADMIN_CREDIT_EXCEEDS_LIMIT` | 422 | Admin-issued credit > Rs 1,000 |

---

### GET /api/v1/customers/me/wallet/balance

**Auth:** `Bearer JWT` (customer)  
**Description:** Get the customer's current wallet balance.

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "customer_id": "customer_uuid",
    "balance": 200.00,
    "expiring_soon": {
      "amount": 50.00,
      "expires_within_days": 30
    }
  },
  "meta": {}
}
```

---

### GET /api/v1/customers/me/wallet/transactions

**Auth:** `Bearer JWT` (customer)  
**Description:** Paginated transaction history for the customer's wallet.

**Query Params:** `?page=1&limit=20&type=CREDIT|DEBIT&from=2026-01-01&to=2026-07-24`

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "transactions": [
      {
        "transaction_id": "txn_uuid",
        "type": "CREDIT",
        "amount": 100.00,
        "balance_before": 100.00,
        "balance_after": 200.00,
        "reason": "REFUND",
        "reference_id": "order_uuid",
        "note": "Refund for cancelled order MED-20260724-015.",
        "expires_at": "2027-07-24T00:00:00Z",
        "created_at": "2026-07-24T14:00:00Z"
      },
      {
        "transaction_id": "txn_uuid_2",
        "type": "DEBIT",
        "amount": 50.00,
        "balance_before": 150.00,
        "balance_after": 100.00,
        "reason": "CHECKOUT",
        "reference_id": "order_uuid_2",
        "note": "Auto-applied at checkout for order MED-20260724-020.",
        "expires_at": null,
        "created_at": "2026-07-24T12:30:00Z"
      }
    ]
  },
  "meta": {
    "page": 1,
    "limit": 20,
    "total": 47
  }
}
```

---

### POST /api/v1/admin/customers/:id/wallet/credit

**Auth:** `Bearer JWT` (admin_finance, admin_support, admin_super)  
**Description:** Admin manual wallet credit (goodwill, dispute resolution).

**Request Body:**
```json
{
  "amount": 75.00,
  "reason": "ADMIN_CREDIT",
  "note": "Goodwill credit for delayed delivery - case #CS-20260724-001."
}
```

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "transaction_id": "txn_uuid",
    "customer_id": "customer_uuid",
    "amount": 75.00,
    "reason": "ADMIN_CREDIT",
    "new_balance": 275.00,
    "credited_by": "admin_uuid",
    "expires_at": "2027-07-24T00:00:00Z",
    "created_at": "2026-07-24T14:05:00Z"
  },
  "meta": {}
}
```

**Errors:**
| Code | HTTP | Meaning |
|---|---|---|
| `CUSTOMER_NOT_FOUND` | 404 | customer_id does not exist |
| `ADMIN_CREDIT_EXCEEDS_LIMIT` | 422 | Amount > Rs 1,000 |
| `INVALID_REASON` | 422 | reason must be `ADMIN_CREDIT` or `CASHBACK` |

---

## Data Models

### WalletAccount

| Field | Type | Nullable | Description |
|---|---|---|---|
| `id` | UUID v4 | No | Primary key |
| `customer_id` | UUID | No | FK ? Customer; unique |
| `balance` | DECIMAL(12,2) | No | Current balance in Rs (default 0.00) |
| `total_credited` | DECIMAL(12,2) | No | Lifetime credits (analytics) |
| `total_debited` | DECIMAL(12,2) | No | Lifetime debits (analytics) |
| `created_at` | TIMESTAMPTZ | No | Account creation |
| `updated_at` | TIMESTAMPTZ | No | Last mutation timestamp |

### WalletTransaction

| Field | Type | Nullable | Description |
|---|---|---|---|
| `id` | UUID v4 | No | Primary key |
| `customer_id` | UUID | No | FK ? Customer; indexed |
| `wallet_id` | UUID | No | FK ? WalletAccount |
| `type` | ENUM(`CREDIT`,`DEBIT`) | No | Transaction direction |
| `amount` | DECIMAL(12,2) | No | Transaction amount in Rs |
| `balance_before` | DECIMAL(12,2) | No | Wallet balance before this transaction |
| `balance_after` | DECIMAL(12,2) | No | Wallet balance after this transaction |
| `reason` | ENUM(`REFUND`,`ADMIN_CREDIT`,`REFERRAL_REWARD`,`CASHBACK`,`CHECKOUT`) | No | Credit/debit reason |
| `reference_id` | UUID | Yes | order_id (checkout/refund) or admin_uuid |
| `note` | TEXT | Yes | Human-readable description |
| `idempotency_key` | VARCHAR(200) | Yes | Dedup key (debits only) |
| `expires_at` | TIMESTAMPTZ | Yes | Credit expiry date; null for debits |
| `credited_by` | UUID | Yes | FK ? AdminUser (admin credits only) |
| `created_at` | TIMESTAMPTZ | No | Transaction timestamp |

---

## Acceptance Criteria

| # | Criterion |
|---|---|
| AC-001 | `POST /wallet/debit` with a valid idempotency_key debits the wallet atomically; re-sending the same idempotency_key returns the original result with `already_processed = true` and does not double-debit. |
| AC-002 | A debit request for Rs 200 on a wallet with Rs 150 balance is rejected with `INSUFFICIENT_BALANCE` (checkout service caps the debit to available balance before calling this endpoint). |
| AC-003 | Wallet balance never goes below 0.00; any scenario that would result in negative balance fails atomically. |
| AC-004 | Credits expire 365 days after issuance; the `GET /wallet/balance` response includes `expiring_soon.amount` for credits expiring within 30 days. |
| AC-005 | FIFO debit order: when a customer has Rs 50 credit expiring in 10 days and Rs 100 credit expiring in 300 days, a debit of Rs 75 consumes the Rs 50 expiring credit first plus Rs 25 from the newer credit. |
| AC-006 | Admin credit via `POST /admin/customers/:id/wallet/credit` > Rs 1,000 returns HTTP 422 `ADMIN_CREDIT_EXCEEDS_LIMIT`. |
| AC-007 | `GET /customers/me/wallet/transactions` returns paginated history with correct `balance_before` and `balance_after` on each entry. |
| AC-008 | A COD refund creates a CREDIT transaction with `reason = REFUND` and the customer's balance is updated immediately. |

---

## Dependencies

| Dependency | Type | Notes |
|---|---|---|
| Payment Processing (EPIC-012/STORY-001) | Internal | Wallet debit at checkout before Razorpay order creation |
| Refund Processing (EPIC-012/STORY-005) | Internal | Refunds trigger wallet credits |
| Order Management (EPIC-010) | Internal | `reference_id` links to order |
| Financial Ledger (EPIC-012/STORY-008) | Internal | Wallet credits/debits create ledger entries |
| Notification Service (EPIC-013) | Internal | Push to customer on wallet credit |
| Scheduled Job Runner | Internal | Daily cron to expire wallet credits past their `expires_at` |

---

## Notes

- The wallet balance on `WalletAccount` is the authoritative source of truth; `balance_before`/`balance_after` on transactions are stored for audit but should always be consistent with the account balance.
- Wallet debit uses `SELECT ... FOR UPDATE` row-level lock on `WalletAccount` to prevent concurrent overdraft in high-concurrency scenarios.
- FIFO consumption of credits requires tracking individual credit tranches; the scheduler marks `WalletTransaction` credits as `expired = true` when their `expires_at` passes and subtracts from the wallet balance in the expiry job.
