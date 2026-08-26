# STORY-008: Financial Ledger

| Field | Value |
|---|---|
| Story ID | EPIC-012/STORY-008 |
| Epic | EPIC-012 - Payments and Finance |
| Title | Financial Ledger |
| Status | Draft |
| Priority | P1 |
| Estimated Effort | 1 Sprint |
| Last Updated | 2026-07-24 |

---

## Overview

The financial ledger is the platform's append-only double-entry-style accounting record for every money movement. Each payment capture, payout, refund, wallet debit/credit, COD deposit, and gateway fee creates a ledger entry. The ledger is never modified - only appended to. Admins can browse the ledger with type and date filters, view a running balance, and export it as CSV for accounting sync with Tally or Zoho Books. The ledger feeds the P&L overview and cash position analytics in STORY-009.

---

## User Roles

| Role | Capability |
|---|---|
| `admin_finance` | View ledger, filter, export CSV |
| `admin_super` | All admin_finance capabilities |

---

## Business Rules

| # | Rule |
|---|---|
| BR-001 | The ledger is **append-only**: no UPDATE or DELETE operations are permitted on `FinancialLedger` records. Corrections are made via offsetting entries. |
| BR-002 | Every money movement creates a ledger entry synchronously within the same database transaction as the triggering event. |
| BR-003 | Ledger entry types: `ORDER_GMV` (payment captured), `COMMISSION` (platform commission on order), `TCS` (TCS deducted from pharmacy), `PAYOUT_PHARMACY`, `PAYOUT_RIDER`, `REFUND`, `WALLET_CREDIT`, `WALLET_DEBIT`, `COD_DEPOSIT`, `GATEWAY_FEE`. |
| BR-004 | `running_balance` is a **computed column** - it is not stored but calculated at query time using a window function over `created_at`; it represents the platform's cumulative cash position at each entry. |
| BR-005 | `credit` entries increase the platform's cash position (e.g., payment captured, COD deposit). `debit` entries decrease it (e.g., payout to pharmacy, refund). |
| BR-006 | The CSV export must include all fields including `credit`, `debit`, `running_balance`, `type`, `reference_id`, `description`, and `created_at`. |
| BR-007 | Ledger access is restricted to `admin_finance` and `admin_super`; no other role can access these endpoints. |
| BR-008 | Gateway fees are captured as debit entries sourced from the `Payment.gateway_fee` field recorded at payment capture time. |

---

## API Endpoints

### GET /api/v1/admin/finance/ledger

**Auth:** `Bearer JWT` (admin_finance, admin_super)  
**Description:** Browse the financial ledger with type and date filters. Supports CSV export via `Accept: text/csv` header.

**Query Params:** `?type=ORDER_GMV|COMMISSION|PAYOUT_PHARMACY|...&from=2026-07-01&to=2026-07-24&page=1&limit=50&sort=created_at&order=desc`

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "kpi_chips": {
      "gmv_today": 185000.00,
      "commission_today": 14800.00,
      "net_revenue_today": 12064.00
    },
    "entries": [
      {
        "ledger_id": "ledger_uuid",
        "type": "ORDER_GMV",
        "reference_id": "order_uuid",
        "reference_type": "ORDER",
        "credit": 495.00,
        "debit": 0.00,
        "running_balance": 248750.00,
        "description": "Payment captured for order MED-20260724-020 (UPI)",
        "created_at": "2026-07-24T13:15:00Z"
      },
      {
        "ledger_id": "ledger_uuid_2",
        "type": "COMMISSION",
        "reference_id": "order_uuid",
        "reference_type": "ORDER",
        "credit": 39.60,
        "debit": 0.00,
        "running_balance": 248789.60,
        "description": "8% commission on order MED-20260724-020 (Rs 495 GMV)",
        "created_at": "2026-07-24T13:15:00Z"
      },
      {
        "ledger_id": "ledger_uuid_3",
        "type": "GATEWAY_FEE",
        "reference_id": "payment_uuid",
        "reference_type": "PAYMENT",
        "credit": 0.00,
        "debit": 8.91,
        "running_balance": 248780.69,
        "description": "Cashfree UPI fee for payment pay_XXXXXXXXXXXX",
        "created_at": "2026-07-24T13:15:00Z"
      },
      {
        "ledger_id": "ledger_uuid_4",
        "type": "PAYOUT_PHARMACY",
        "reference_id": "settlement_uuid",
        "reference_type": "SETTLEMENT",
        "credit": 0.00,
        "debit": 47320.00,
        "running_balance": 201460.69,
        "description": "Settlement released to Apollo Pharmacy, Koramangala (cycle 2026-07-14 to 2026-07-20)"
      }
    ]
  },
  "meta": {
    "page": 1,
    "limit": 50,
    "total": 4821
  }
}
```

---

### GET /api/v1/admin/finance/ledger/export

**Auth:** `Bearer JWT` (admin_finance, admin_super)  
**Description:** Export ledger entries as CSV for a given date range.

**Query Params:** `?from=2026-07-01&to=2026-07-31&type=<optional>`

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "download_url": "https://s3.amazonaws.com/medmate-exports/ledger_2026-07.csv",
    "expires_at": "2026-07-25T01:00:00Z",
    "record_count": 12450,
    "from_date": "2026-07-01",
    "to_date": "2026-07-31",
    "generated_at": "2026-07-24T16:30:00Z"
  },
  "meta": {}
}
```

**Errors:**
| Code | HTTP | Meaning |
|---|---|---|
| `INVALID_DATE_RANGE` | 422 | `from_date` is after `to_date` |
| `DATE_RANGE_TOO_LARGE` | 422 | Range exceeds 90 days |

---

## Data Models

### FinancialLedger

| Field | Type | Nullable | Description |
|---|---|---|---|
| `id` | UUID v4 | No | Primary key |
| `type` | ENUM(`ORDER_GMV`,`COMMISSION`,`TCS`,`PAYOUT_PHARMACY`,`PAYOUT_RIDER`,`REFUND`,`WALLET_CREDIT`,`WALLET_DEBIT`,`COD_DEPOSIT`,`GATEWAY_FEE`) | No | Entry type |
| `reference_id` | UUID | No | ID of the source record (order, settlement, payout, etc.) |
| `reference_type` | ENUM(`ORDER`,`SETTLEMENT`,`RIDER_PAYOUT`,`REFUND`,`PAYMENT`,`WALLET_TRANSACTION`,`COD_DEPOSIT`) | No | Type of source record |
| `credit` | DECIMAL(14,2) | No | Amount flowing into platform (0 if debit) |
| `debit` | DECIMAL(14,2) | No | Amount flowing out of platform (0 if credit) |
| `description` | TEXT | No | Human-readable description for accounting |
| `metadata` | JSONB | Yes | Additional context (order number, pharmacy name, etc.) |
| `created_at` | TIMESTAMPTZ | No | Entry creation timestamp (immutable) |

*No UPDATE or DELETE operations permitted. The table has a check constraint: `(credit > 0 AND debit = 0) OR (credit = 0 AND debit > 0)` - every entry is either a credit or a debit, not both.*

---

### Ledger Entry Triggers

| Trigger Event | Entry Type | Credit/Debit |
|---|---|---|
| Payment captured (UPI/card) | `ORDER_GMV` | Credit: `payment.amount` |
| Payment captured | `COMMISSION` | Credit: `gmv - commission_pct` |
| Payment captured | `GATEWAY_FEE` | Debit: `payment.gateway_fee` |
| TCS deducted on settlement | `TCS` | Credit: `gmv - 0.01` |
| Pharmacy settlement released | `PAYOUT_PHARMACY` | Debit: `settlement.net_payable` |
| Rider payout released | `PAYOUT_RIDER` | Debit: `payout.net_payout` |
| Refund processed (online) | `REFUND` | Debit: `refund.gateway_refund_amount` |
| Wallet credit issued | `WALLET_CREDIT` | Debit: `transaction.amount` (cash out of platform) |
| Wallet debit at checkout | `WALLET_DEBIT` | Credit: `transaction.amount` (wallet used = platform doesn't need to pay out) |
| COD deposit confirmed | `COD_DEPOSIT` | Credit: `deposit.amount` |

---

## Acceptance Criteria

| # | Criterion |
|---|---|
| AC-001 | Every payment capture creates three `FinancialLedger` entries atomically: `ORDER_GMV` (credit), `COMMISSION` (credit), and `GATEWAY_FEE` (debit). |
| AC-002 | No UPDATE or DELETE SQL operations succeed on the `FinancialLedger` table; the table has DB-level constraints enforcing append-only behaviour. |
| AC-003 | `GET /admin/finance/ledger?type=PAYOUT_PHARMACY` returns only payout entries; `running_balance` is computed correctly as a window sum. |
| AC-004 | `GET /admin/finance/ledger/export?from=2026-07-01&to=2026-07-31` returns a download URL for a CSV with all 12,450 entries for July. |
| AC-005 | Export request for a range > 90 days returns HTTP 422 `DATE_RANGE_TOO_LARGE`. |
| AC-006 | `kpi_chips` in the ledger response (`gmv_today`, `commission_today`, `net_revenue_today`) are computed from ledger entries for the current calendar day. |
| AC-007 | Any role other than `admin_finance` or `admin_super` calling any ledger endpoint receives HTTP 403. |
| AC-008 | The CSV export contains columns: `ledger_id`, `type`, `reference_id`, `reference_type`, `credit`, `debit`, `running_balance`, `description`, `created_at` - compatible with Tally/Zoho import format. |

---

## Dependencies

| Dependency | Type | Notes |
|---|---|---|
| Payment Processing (EPIC-012/STORY-001) | Internal | Payment capture triggers `ORDER_GMV`, `COMMISSION`, `GATEWAY_FEE` entries |
| Pharmacy Settlements (EPIC-012/STORY-003) | Internal | Settlement release triggers `TCS` and `PAYOUT_PHARMACY` entries |
| Rider Payouts (EPIC-012/STORY-004) | Internal | Payout release triggers `PAYOUT_RIDER` entries |
| Refund Processing (EPIC-012/STORY-005) | Internal | Refund completion triggers `REFUND` entries |
| Wallet Operations (EPIC-012/STORY-002) | Internal | Wallet mutations trigger `WALLET_CREDIT`/`WALLET_DEBIT` entries |
| COD Float (EPIC-011/STORY-007, EPIC-012/STORY-006) | Internal | COD deposit confirmation triggers `COD_DEPOSIT` entries |
| AWS S3 | External | CSV export storage |

---

## Notes

- `running_balance` is computed using PostgreSQL window function: `SUM(credit - debit) OVER (ORDER BY created_at ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)`. This is computed at query time and included in the JSON response; it is also computed at CSV export time.
- The export CSV is generated asynchronously for large date ranges; a background job generates the file and the S3 URL is returned. The URL is pre-signed and expires after 1 hour.
- Ledger entries with the same `reference_id` and `reference_type` can coexist (e.g., `ORDER_GMV` and `COMMISSION` both reference the same `order_id`); no uniqueness constraint on these fields.
