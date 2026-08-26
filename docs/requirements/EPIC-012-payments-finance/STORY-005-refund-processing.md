# STORY-005: Refund Processing

| Field | Value |
|---|---|
| Story ID | EPIC-012/STORY-005 |
| Epic | EPIC-012 - Payments and Finance |
| Title | Refund Processing |
| Status | Draft |
| Priority | P0 |
| Estimated Effort | 2 Sprints |
| Last Updated | 2026-07-24 |

---

## Overview

This story governs the complete lifecycle of customer refunds from the point of order cancellation to fund disbursement. Refund routing depends on the original payment method: COD cancellations go to the Namma Money wallet instantly; online payments go to the source account via the Cashfree Refund API (3-5 business days); wallet-paid portions return to the wallet instantly. Refunds below Rs 500 for system/pharmacy cancellations are auto-processed; refunds above Rs 500 require admin_finance approval. Admins manage the refund queue through the finance module, and customers can track refund status in the Customer App.

---

## User Roles

| Role | Capability |
|---|---|
| `admin_finance` | View refund queue, process refunds, view detail |
| `admin_support` | View refund queue (read-only) |
| `admin_super` | All admin_finance capabilities |
| `customer` | View own refund history and status |
| System | Auto-initiate refunds for eligible cancellations |

---

## Business Rules

| # | Rule |
|---|---|
| BR-001 | **Refund routing logic:** `if payment_method == COD ? refund_to = WALLET` (instant). `if payment_method == ONLINE and amount <= wallet_portion ? refund_to = WALLET`. `if online payment and gateway_portion > 0 ? refund_to = SOURCE_ACCOUNT` via Cashfree refund API. |
| BR-002 | **Auto-refund threshold:** orders cancelled by the pharmacy or system (not customer-initiated) with `refund_amount ? Rs 500` are automatically approved and processed without admin action. |
| BR-003 | Refunds above Rs 500 are queued with `status = PENDING` and require explicit `admin_finance` approval via `POST /admin/finance/refunds/:id/process`. |
| BR-004 | The refund SLA is **24 hours** from order cancellation to admin processing. Refunds older than 24 hours without action are flagged as overdue in the dashboard. |
| BR-005 | **Partial refunds** are supported for dispute partial resolutions; the `refund_amount` can be less than the order total. The `notes` field is required for partial refunds to document the reason. |
| BR-006 | Online payment refunds via Cashfree are expected to reach the customer's source account in **3-5 business days**; the status is updated to `COMPLETED` when Cashfree sends the `refund.processed` webhook. |
| BR-007 | A refund is idempotent per `order_id` - attempting to create a second refund for a fully refunded order returns `ORDER_ALREADY_REFUNDED`. |
| BR-008 | COD refunds and wallet refunds are instant; `status = COMPLETED` is set immediately upon wallet credit. |

---

## API Endpoints

### GET /api/v1/admin/finance/refunds

**Auth:** `Bearer JWT` (admin_finance, admin_support, admin_super)  
**Description:** Refund management queue with KPI chips.

**Query Params:** `?status=PENDING&refund_to=SOURCE_ACCOUNT|WALLET&page=1&limit=20&from=2026-07-01`

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "kpi_chips": {
      "pending_count": 12,
      "pending_value": 8450.00,
      "processed_today": 24,
      "failed_today": 1,
      "overdue_count": 3
    },
    "refunds": [
      {
        "refund_id": "refund_uuid",
        "order_id": "order_uuid",
        "order_number": "MED-20260724-018",
        "customer_name": "Priya S",
        "customer_phone": "9876543210",
        "refund_amount": 450.00,
        "payment_method": "UPI",
        "refund_to": "SOURCE_ACCOUNT",
        "status": "PENDING",
        "cancellation_reason": "PHARMACY_CANCELLED",
        "is_overdue": false,
        "created_at": "2026-07-24T10:00:00Z"
      }
    ]
  },
  "meta": {
    "page": 1,
    "limit": 20,
    "total": 36
  }
}
```

---

### POST /api/v1/admin/finance/refunds/:refund_id/process

**Auth:** `Bearer JWT` (admin_finance, admin_super)  
**Description:** Process a pending refund. Triggers Cashfree refund API (online) or wallet credit (COD/wallet).

**Request Body:**
```json
{
  "notes": "Approved. Pharmacy confirmed they cancelled the order."
}
```

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "refund_id": "refund_uuid",
    "order_id": "order_uuid",
    "refund_amount": 450.00,
    "refund_to": "SOURCE_ACCOUNT",
    "status": "PROCESSING",
    "cashfree_refund_id": "rfnd_XXXXXXXXXXXX",
    "expected_by": "2026-07-29",
    "processed_by": "admin_uuid",
    "processed_at": "2026-07-24T10:30:00Z"
  },
  "meta": {}
}
```

**Errors:**
| Code | HTTP | Meaning |
|---|---|---|
| `REFUND_NOT_FOUND` | 404 | refund_id does not exist |
| `REFUND_ALREADY_PROCESSED` | 409 | Refund not in PENDING state |
| `CASHFREE_REFUND_FAILED` | 502 | Cashfree API returned an error |

---

### GET /api/v1/admin/finance/refunds/:refund_id

**Auth:** `Bearer JWT` (admin_finance, admin_support, admin_super)  
**Description:** Refund detail with full order and payment context.

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "refund_id": "refund_uuid",
    "order_id": "order_uuid",
    "order_number": "MED-20260724-018",
    "order_total": 495.00,
    "refund_amount": 450.00,
    "is_partial": true,
    "payment_method": "UPI",
    "wallet_portion_original": 50.00,
    "gateway_portion_original": 445.00,
    "wallet_refund_amount": 45.00,
    "gateway_refund_amount": 405.00,
    "refund_to": "SOURCE_ACCOUNT",
    "status": "PROCESSING",
    "cancellation_reason": "PHARMACY_CANCELLED",
    "cashfree_refund_id": "rfnd_XXXXXXXXXXXX",
    "cashfree_payment_id": "pay_XXXXXXXXXXXX",
    "expected_by": "2026-07-29",
    "customer": {
      "name": "Priya S",
      "phone": "9876543210",
      "email": "priya.s@example.com"
    },
    "notes": "Approved. Pharmacy confirmed they cancelled the order.",
    "created_at": "2026-07-24T10:00:00Z",
    "processed_at": "2026-07-24T10:30:00Z"
  },
  "meta": {}
}
```

---

### GET /api/v1/customers/me/refunds

**Auth:** `Bearer JWT` (customer)  
**Description:** Customer's refund history and status.

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "refunds": [
      {
        "refund_id": "refund_uuid",
        "order_id": "order_uuid",
        "order_number": "MED-20260724-018",
        "amount": 450.00,
        "refund_to": "SOURCE_ACCOUNT",
        "status": "PROCESSING",
        "expected_by": "2026-07-29",
        "message": "Your refund is being processed and will be credited to your original payment method within 3-5 business days.",
        "created_at": "2026-07-24T10:00:00Z"
      }
    ]
  },
  "meta": {
    "page": 1,
    "limit": 20,
    "total": 3
  }
}
```

---

## Data Models

### Refund

| Field | Type | Nullable | Description |
|---|---|---|---|
| `id` | UUID v4 | No | Primary key |
| `order_id` | UUID | No | FK ? Order; indexed |
| `customer_id` | UUID | No | FK ? Customer |
| `payment_id` | UUID | No | FK ? Payment |
| `refund_amount` | DECIMAL(12,2) | No | Amount to be refunded |
| `is_partial` | BOOLEAN | No | Whether refund is less than order total |
| `wallet_refund_amount` | DECIMAL(12,2) | No | Portion going to wallet |
| `gateway_refund_amount` | DECIMAL(12,2) | No | Portion going to source account |
| `payment_method` | ENUM(`UPI`,`CARD`,`COD`,`WALLET_ONLY`) | No | Original payment method |
| `refund_to` | ENUM(`SOURCE_ACCOUNT`,`WALLET`) | No | Destination of refund |
| `status` | ENUM(`PENDING`,`PROCESSING`,`COMPLETED`,`FAILED`) | No | Refund lifecycle |
| `cancellation_reason` | VARCHAR(100) | No | Why the order was cancelled |
| `cashfree_refund_id` | VARCHAR(100) | Yes | Cashfree refund reference |
| `cashfree_payment_id` | VARCHAR(100) | Yes | Original payment reference |
| `auto_processed` | BOOLEAN | No | True if auto-approved (? Rs 500 threshold) |
| `is_overdue` | BOOLEAN | No | Computed: created > 24h ago and still PENDING |
| `expected_by` | DATE | Yes | Expected completion date (5 business days) |
| `processed_by` | UUID | Yes | FK ? AdminUser |
| `processed_at` | TIMESTAMPTZ | Yes | Processing timestamp |
| `notes` | TEXT | Yes | Admin notes |
| `created_at` | TIMESTAMPTZ | No | Refund creation (order cancellation) |
| `completed_at` | TIMESTAMPTZ | Yes | When refund reached COMPLETED state |

---

## Acceptance Criteria

| # | Criterion |
|---|---|
| AC-001 | A pharmacy-cancelled order with UPI payment ? Rs 500 is auto-refunded to the source account within 10 minutes; `auto_processed = true`. |
| AC-002 | A refund > Rs 500 remains in `PENDING` state until admin_finance explicitly processes it. |
| AC-003 | A COD cancellation of any amount creates a wallet credit immediately; `refund_to = WALLET`, `status = COMPLETED`. |
| AC-004 | When the Cashfree `refund.processed` webhook arrives, the refund status is updated to `COMPLETED`; the customer receives a push notification. |
| AC-005 | A partial refund (e.g., Rs 450 on a Rs 495 order) creates a `Refund` record with `is_partial = true`; the notes field is mandatory for partial refunds. |
| AC-006 | `GET /admin/finance/refunds?status=PENDING` shows `is_overdue = true` for refunds older than 24 hours without action. |
| AC-007 | Attempting to process an already-COMPLETED refund returns HTTP 409 `REFUND_ALREADY_PROCESSED`. |
| AC-008 | For a hybrid payment (Rs 50 wallet + Rs 445 UPI), the refund for full amount credits Rs 50 to wallet immediately and initiates a Rs 445 Cashfree refund. |

---

## Dependencies

| Dependency | Type | Notes |
|---|---|---|
| Payment Processing (EPIC-012/STORY-001) | Internal | Cashfree payment_id used for refund API call |
| Wallet Operations (EPIC-012/STORY-002) | Internal | Wallet credit on COD/wallet refunds |
| Order Management (EPIC-010) | Internal | Cancellation event triggers refund creation |
| Cashfree Refund API | External | Online payment refund disbursement |
| Cashfree Webhook (EPIC-012/STORY-001) | External | `refund.processed` event updates status to COMPLETED |
| Financial Ledger (EPIC-012/STORY-008) | Internal | Ledger entry on refund completion |
| Notification Service (EPIC-013) | Internal | Customer push on refund completion |

---

## Notes

- The `is_overdue` flag on refunds is computed at query time (`created_at < now() - 24h AND status = PENDING`); it is not stored persistently.
- For UPI and card refunds, the actual credit to the customer's account is handled by the bank/UPI network after Cashfree processes the refund; Namma MedMate has no control over the final delivery timing.
- When a Cashfree refund fails, the `Refund` status is set to `FAILED`; admin_finance is alerted and must investigate the Cashfree dashboard.
