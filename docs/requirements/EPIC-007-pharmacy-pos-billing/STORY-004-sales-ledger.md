# STORY-004: Sales Ledger - Complete Sales Audit Trail

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-004 |
| **Epic** | EPIC-007 - Pharmacy POS & Billing |
| **Priority** | P0 |
| **Complexity** | M |
| **Status** | Draft |

---

## Overview

This story provides the comprehensive sales ledger for the Pharmacy Dashboard - a unified view of all revenue events across counter sales and online orders. The ledger is the pharmacist's primary financial audit tool, offering date-range summaries, detailed per-sale records, payment-mode analytics, and Excel/PDF exports. It is available on the Free plan as a core ERP feature. An important action endpoint allows marking credit/unpaid sales as paid when a customer settles their bill outside the normal Khata flow.

---

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| `pharmacy_owner` | Full read + write | View full ledger, export, mark as paid |
| `pharmacy_staff` | Read-only | View ledger and individual sales |
| `admin_finance` | Read-only | Cross-pharmacy financial reporting |
| `admin_compliance` | Read-only | Tax audit access |
| `customer` | No access | Not applicable |

---

## Business Rules

1. **Unified ledger.** The sales ledger aggregates ALL revenue events - counter POS sales and online orders - into a single chronological list. Each row has a `channel` tag: `COUNTER` or `ONLINE`.
2. **Financial year filter.** India financial year runs April 1 - March 31. The ledger supports a financial year filter that auto-populates `from_date = Apr 1` and `to_date = Mar 31` for the selected year.
3. **Maximum export range.** Ledger exports are capped at 12 months per export request. If the date range exceeds 12 months, the API returns `EXPORT_RANGE_TOO_LARGE`.
4. **Totals footer always included.** Regardless of pagination, the API response always includes aggregate totals for the current filter set (not just the current page). This allows the UI to show running totals at the bottom.
5. **Mark as paid flow.** `POST /sales/:sale_id/mark-paid` is used to record payment against a CREDIT or PENDING invoice that was settled outside the POS flow (e.g., bank transfer). This creates a `KhataEntry` of type CREDIT if the invoice had a CREDIT payment method.
6. **Day-end summary.** The sales summary endpoint can be used in "today" mode to generate a day-end cash register summary with `total_bills`, `total_revenue`, `cash_total`, `upi_total`, etc.
7. **Search scope.** The ledger search (`q`) matches across `invoice_number`, `customer_name`, and `customer_phone`. Partial matches are supported (prefix search at minimum).
8. **Sort options.** Ledger is sortable by `date`, `amount`, and `invoice_number` in ascending or descending order.

---

## API Endpoints

### 1. Sales Ledger List

```
GET /api/v1/pharmacy/sales
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 60 req/min

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `from_date` | date | start of current month | Date range start (YYYY-MM-DD) |
| `to_date` | date | today | Date range end |
| `channel` | enum | - | `COUNTER \| ONLINE` |
| `payment_method` | enum | - | `CASH \| UPI \| CARD \| COD \| CREDIT \| INSURANCE_TPA` |
| `payment_status` | enum | - | `PAID \| PENDING \| PARTIAL` |
| `q` | string | - | Invoice number, customer name, phone |
| `sort` | enum | `date` | `date \| amount \| invoice_number` |
| `order` | enum | `desc` | `asc \| desc` |
| `page` | integer | `1` | Page number |
| `limit` | integer | `20` | Items per page (max 100) |
| `export` | enum | - | `EXCEL \| PDF` |
| `financial_year` | string | - | e.g., `2025-26` - overrides from/to dates |

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "period_summary": {
      "from_date": "2026-07-01",
      "to_date": "2026-07-24",
      "bill_count": 486,
      "units_sold": 12400,
      "gross_revenue": 248600.00,
      "gst_collected": 22450.00,
      "net_collected": 226150.00,
      "credit_outstanding": 15400.00
    },
    "sales": [
      {
        "sale_id": "uuid",
        "invoice_number": "INV-2026-07-000042",
        "date": "2026-07-24T12:15:00Z",
        "channel": "COUNTER",
        "customer_name": "Priya Sharma",
        "customer_phone": "+919876000001",
        "items_count": 3,
        "grand_total": 450.00,
        "gst_total": 48.21,
        "payment_method": "CASH",
        "payment_status": "PAID"
      }
    ]
  },
  "meta": {
    "page": 1,
    "limit": 20,
    "total": 486
  }
}
```

---

### 2. Sales Summary (Analytics)

```
GET /api/v1/pharmacy/sales/summary
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 30 req/min

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `from_date` | date | start of current month | Date range start |
| `to_date` | date | today | Date range end |

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "period": { "from": "2026-07-01", "to": "2026-07-24" },
    "total_bills": 486,
    "total_revenue": 248600.00,
    "avg_bill_value": 511.52,
    "online_vs_counter": {
      "online_revenue": 62000.00,
      "online_pct": 24.9,
      "counter_revenue": 186600.00,
      "counter_pct": 75.1
    },
    "payment_mode_mix": {
      "CASH": { "count": 210, "amount": 98400.00 },
      "UPI": { "count": 185, "amount": 88200.00 },
      "CARD": { "count": 42, "amount": 28000.00 },
      "CREDIT": { "count": 49, "amount": 34000.00 }
    },
    "top_selling_categories": [
      { "category_name": "Antibiotics", "revenue": 42000.00, "units": 1840 },
      { "category_name": "Analgesics", "revenue": 36000.00, "units": 2200 }
    ],
    "top_selling_products": [
      { "product_name": "Paracetamol 500mg Tab", "revenue": 18000.00, "units": 800 }
    ]
  }
}
```

---

### 3. Sale Detail

```
GET /api/v1/pharmacy/sales/:sale_id
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 120 req/min

**Success Response - 200 OK:**

Returns the same response as `GET /api/v1/pharmacy/invoices/:invoice_id` (STORY-002). The `sale_id` is the same as `invoice_id`.

---

### 4. Mark Sale as Paid

```
POST /api/v1/pharmacy/sales/:sale_id/mark-paid
```

**Authentication:** Bearer JWT - `pharmacy_owner`
**Rate Limit:** 20 req/min

**Request Body (application/json):**

```json
{
  "payment_mode": "CASH | UPI | CARD - required",
  "amount": "number > 0 - required",
  "reference_number": "string max 50 - optional",
  "note": "string max 300 - optional"
}
```

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "sale_id": "uuid",
    "invoice_number": "INV-2026-07-000042",
    "previous_payment_status": "PENDING",
    "new_payment_status": "PAID",
    "amount_settled": 450.00,
    "settled_at": "2026-07-24T14:00:00Z",
    "receipt_number": "RCPT-2026-07-000014"
  }
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `SALE_ALREADY_PAID` | Invoice already has `payment_status = PAID` |
| 400 | `AMOUNT_MISMATCH` | `amount` does not match or exceed outstanding |
| 403 | `STAFF_CANNOT_MARK_PAID` | Only `pharmacy_owner` may mark sales as paid |
| 404 | `SALE_NOT_FOUND` | Sale ID not found for this pharmacy |

---

## Data Models

> The Sales Ledger does not introduce new data models. It reads from the `Invoice` table (defined in STORY-002) with additional joins.

### Key Invoice Query Fields Used

| Field | Source Table | Description |
|-------|-------------|-------------|
| `invoice_number` | Invoice | Sequential invoice number |
| `channel` | Invoice | COUNTER or ONLINE |
| `grand_total` | Invoice | Final sale amount |
| `payment_method` | Invoice | Payment type |
| `payment_status` | Invoice | PAID / PENDING / PARTIAL |
| `customer_name` | Invoice | Customer name |
| `gst_total` | Invoice | GST component |
| `created_at` | Invoice | Sale timestamp |

---

## Acceptance Criteria

- [ ] Given `GET /sales?from_date=2026-07-01&to_date=2026-07-24`, then `period_summary.bill_count` equals the exact count of invoices in that date range across both COUNTER and ONLINE channels.
- [ ] Given `GET /sales?export=EXCEL&from_date=2025-04-01&to_date=2026-03-31`, then a valid `.xlsx` file is returned with all 12 months of sales data.
- [ ] Given `GET /sales?from_date=2024-01-01&to_date=2026-01-01` (>12 months), when `export=EXCEL`, then a 400 `EXPORT_RANGE_TOO_LARGE` error is returned.
- [ ] Given `GET /sales?financial_year=2025-26`, then `from_date` is auto-set to `2025-04-01` and `to_date` to `2026-03-31`.
- [ ] Given `POST /sales/:sale_id/mark-paid` on a PENDING CREDIT sale, then `payment_status` changes to `PAID`, a `KhataEntry` CREDIT is created, and a receipt number is returned.
- [ ] Given `POST /sales/:sale_id/mark-paid` on an already-PAID invoice, then a 400 `SALE_ALREADY_PAID` error is returned.
- [ ] Given `GET /sales/summary?from_date=2026-07-01&to_date=2026-07-24`, then `online_vs_counter` percentages sum to 100%.
- [ ] Given `GET /sales` with no filters, then the response always includes `period_summary` totals covering the default date range regardless of the current page.

---

## Dependencies

- **EPIC-007 / STORY-002 (Invoice Management):** Sales ledger reads from the `Invoice` table.
- **EPIC-007 / STORY-003 (Khata):** `mark-paid` for CREDIT invoices creates a Khata repayment entry.
- **EPIC-004 (Online Orders):** Online orders appear in the ledger with `channel = ONLINE`.
- **EPIC-008 (Reports):** Sales ledger data feeds P&L and GST reports.

---

## Notes

- The ledger aggregation query (period_summary totals) should use indexed `created_at` and `pharmacy_id` columns for performance. For pharmacies with > 10k monthly transactions, consider a daily aggregate materialized view.
- `GET /sales/:sale_id` is an alias for `GET /invoices/:invoice_id`. The frontend may use either path; both return identical response shapes.
- Day-end summary is `GET /sales/summary?from_date=today&to_date=today` - no separate endpoint is needed.
