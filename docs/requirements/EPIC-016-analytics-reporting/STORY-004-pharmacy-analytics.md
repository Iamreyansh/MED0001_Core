# STORY-004: Pharmacy Analytics

| Field | Value |
|-------|-------|
| Story ID | EPIC-016-STORY-004 |
| Epic | EPIC-016 Analytics and Reporting |
| Title | Pharmacy Analytics |
| Priority | P1 |
| Status | In Development |
| Role | pharmacy_owner, pharmacy_staff |
| Last Updated | 2026-07-24 |

## Overview

The Pharmacy Analytics story delivers a self-serve analytics and reporting suite within the Pharmacy Dashboard, giving pharmacy owners visibility into their net revenue, gross profit, margin, GST liability, and product-level performance. It includes a sales register for transaction-level drill-down, a product analytics table for inventory and profitability insights, a GST accounts view with P&L and GST slab breakdown, and a configurable report catalogue for common pre-built reports. The analytics module is gated to Growth plan and above; Free and Starter plan pharmacies receive a 403 with an upgrade prompt.

## User Roles

| Role | Access |
|------|--------|
| pharmacy_owner | Full read access to their pharmacy's analytics |
| pharmacy_staff | Read-only access (owner-granted) to analytics |
| admin_super | Can impersonate to view any pharmacy's analytics |
| admin_operations | Can view any pharmacy's analytics |
| customer | No access |

## Business Rules

1. **Plan Gating**: All pharmacy analytics endpoints require the pharmacy to be on `GROWTH`, `RETAIL_PRO`, or `ENTERPRISE` plan. Pharmacies on `FREE` or `STARTER` receive `403 PLAN_UPGRADE_REQUIRED` with an upgrade prompt URL.
2. **Scope Enforcement**: Pharmacy analytics are strictly scoped to the authenticated pharmacy. A pharmacy's JWT cannot access another pharmacy's data. Admin impersonation uses a separate admin-scoped endpoint with `pharmacy_id` query param.
3. **Fiscal Year**: When `period=FY`, the date range is April 1 00:00 IST to March 31 23:59 IST of the current financial year. If today is before April 1, it defaults to the previous FY.
4. **Gross Profit Computation**: `gross_profit = revenue - cogs`. COGS is recorded per inventory batch. If COGS data is unavailable for a product, it is excluded from margin calculations and flagged in the response.
5. **Net Profit Computation**: `net_profit = gross_profit - operating_expenses - net_gst_payable`. Operating expenses are entered manually by the pharmacy owner.
6. **GST Slab Breakdown**: GST liability is reported separately for 5%, 12%, and 18% slabs. `net_gst_payable = output_gst_collected - input_itc`. Input ITC is sourced from purchase invoices entered in the ERP.
7. **Dead Stock Flag**: A product is flagged as `dead_stock` if: (a) no units sold in the last 90 days AND (b) current stock > 0. The flag is computed daily at 02:00 IST.
8. **Day Book**: The day book (in the accounts-gst endpoint) is a chronological ledger of all sales and purchases for the period. One row per transaction. Each row includes a running balance.
9. **Report Export**: Reports from the catalogue can be exported in Excel (.xlsx) and PDF formats. Export is async for > 500 rows; synchronous for ? 500 rows.
10. **Channel Mix**: `channel_mix` distinguishes `ONLINE` (orders placed via the customer app) and `COUNTER` (walk-in POS sales entered via pharmacy ERP).

## API Endpoints

### GET /api/v1/pharmacy/analytics/overview

Retrieve pharmacy-level analytics KPI overview.

**Auth**: Bearer JWT - `pharmacy_owner`, `pharmacy_staff`

**Query Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| period | string | Yes | `7D`, `30D`, `12M`, `FY`, `CUSTOM` |
| date_from | string | No | Required when CUSTOM |
| date_to | string | No | Required when CUSTOM |

**Response 200**
```json
{
  "success": true,
  "data": {
    "pharmacy_id": "uuid-ph-1",
    "period": "30D",
    "date_from": "2026-06-24",
    "date_to": "2026-07-24",
    "financials": {
      "net_revenue_paise": 2840000,
      "gross_profit_paise": 682000,
      "margin_pct": 24.0,
      "units_sold": 4120,
      "net_gst_paise": 142000
    },
    "top_items": [
      { "product_id": "uuid-p-1", "name": "Metformin 500mg", "units_sold": 412, "revenue_paise": 82400 },
      { "product_id": "uuid-p-2", "name": "Atorvastatin 10mg", "units_sold": 348, "revenue_paise": 69600 }
    ],
    "channel_mix": {
      "online_pct": 68.4,
      "counter_pct": 31.6
    },
    "payment_mix": [
      { "method": "UPI",  "pct": 54.2 },
      { "method": "CARD", "pct": 22.1 },
      { "method": "CASH", "pct": 18.4 },
      { "method": "WALLET", "pct": 5.3 }
    ]
  },
  "meta": {}
}
```

**Error Table**

| HTTP Code | Error Code | Condition |
|-----------|-----------|-----------|
| 400 | INVALID_PERIOD | Period not in allowed set |
| 403 | PLAN_UPGRADE_REQUIRED | Pharmacy on Free/Starter plan |
| 403 | FORBIDDEN | Accessing another pharmacy's data |

---

### GET /api/v1/pharmacy/analytics/sales-register

Retrieve all individual sales transactions for the period.

**Auth**: Bearer JWT - `pharmacy_owner`, `pharmacy_staff`

**Query Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| period | string | Yes | Period selector |
| date_from | string | No | Required when CUSTOM |
| date_to | string | No | Required when CUSTOM |
| channel | string | No | `ONLINE`, `COUNTER` |
| payment_method | string | No | Filter by payment method |
| page | integer | No | Default 1 |
| limit | integer | No | Default 20, max 100 |

**Response 200**
```json
{
  "success": true,
  "data": {
    "sales": [
      {
        "sale_id": "uuid-s-1",
        "invoice_number": "INV-2026-07-001",
        "sale_date": "2026-07-24T10:30:00Z",
        "channel": "ONLINE",
        "customer_name": "Ravi Kumar",
        "items_count": 3,
        "subtotal_paise": 48000,
        "gst_paise": 2400,
        "total_paise": 50400,
        "payment_method": "UPI",
        "status": "DELIVERED"
      }
    ],
    "totals": {
      "total_sales": 148,
      "total_revenue_paise": 2840000,
      "total_gst_paise": 142000
    }
  },
  "meta": {
    "page": 1,
    "limit": 20,
    "total": 148
  }
}
```

---

### GET /api/v1/pharmacy/analytics/products

Retrieve product-level analytics: sales, revenue, margin, stock, dead stock.

**Auth**: Bearer JWT - `pharmacy_owner`, `pharmacy_staff`

**Query Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| period | string | Yes | Period selector |
| sort | string | No | `units_sold`, `revenue`, `margin_pct`, `profit` (default: `revenue`) |
| order | string | No | `asc`, `desc` (default: `desc`) |
| dead_stock_only | boolean | No | Filter to dead stock items only |

**Response 200**
```json
{
  "success": true,
  "data": {
    "products": [
      {
        "product_id": "uuid-p-1",
        "name": "Metformin 500mg",
        "category": "PRESCRIPTION",
        "units_sold": 412,
        "revenue_paise": 82400,
        "cogs_paise": 61800,
        "profit_paise": 20600,
        "margin_pct": 25.0,
        "stock_remaining": 840,
        "dead_stock_flag": false
      },
      {
        "product_id": "uuid-p-10",
        "name": "Vitamin C 500mg (Expired Batch)",
        "category": "OTC",
        "units_sold": 0,
        "revenue_paise": 0,
        "cogs_paise": 0,
        "profit_paise": 0,
        "margin_pct": null,
        "stock_remaining": 240,
        "dead_stock_flag": true
      }
    ]
  },
  "meta": {
    "page": 1,
    "limit": 20,
    "total": 184
  }
}
```

---

### GET /api/v1/pharmacy/analytics/accounts-gst

Retrieve P&L, GST liability, cash summary, and day book for the period.

**Auth**: Bearer JWT - `pharmacy_owner`

**Query Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| period | string | Yes | Period selector |
| date_from | string | No | Required when CUSTOM |
| date_to | string | No | Required when CUSTOM |

**Response 200**
```json
{
  "success": true,
  "data": {
    "pl_card": {
      "revenue_paise": 2840000,
      "cogs_paise": 2158000,
      "gross_profit_paise": 682000,
      "operating_expenses_paise": 124000,
      "net_gst_payable_paise": 142000,
      "net_profit_paise": 416000
    },
    "gst_liability": {
      "output_gst_paise": 208000,
      "input_itc_paise": 66000,
      "net_payable_paise": 142000,
      "slab_breakdown": [
        { "slab_pct": 5,  "taxable_value_paise": 840000, "output_gst_paise": 42000, "input_itc_paise": 18000, "net_paise": 24000 },
        { "slab_pct": 12, "taxable_value_paise": 620000, "output_gst_paise": 74400, "input_itc_paise": 24000, "net_paise": 50400 },
        { "slab_pct": 18, "taxable_value_paise": 520000, "output_gst_paise": 93600, "input_itc_paise": 24000, "net_paise": 69600 }
      ]
    },
    "cash_summary": {
      "total_collections_paise": 2840000,
      "cash_collected_paise": 520000,
      "digital_collected_paise": 2320000
    },
    "purchases_summary": {
      "total_purchases_paise": 2080000,
      "gst_on_purchases_paise": 66000
    },
    "day_book": [
      {
        "date": "2026-07-24",
        "type": "SALE",
        "reference": "INV-2026-07-001",
        "description": "Online order #ORD-8821",
        "debit_paise": 0,
        "credit_paise": 50400,
        "balance_paise": 50400
      },
      {
        "date": "2026-07-24",
        "type": "PURCHASE",
        "reference": "PUR-2026-07-014",
        "description": "Stock from Medley Pharma",
        "debit_paise": 84000,
        "credit_paise": 0,
        "balance_paise": -33600
      }
    ]
  },
  "meta": {}
}
```

---

### GET /api/v1/pharmacy/analytics/reports-catalogue

List all pre-built reports available to the pharmacy.

**Auth**: Bearer JWT - `pharmacy_owner`, `pharmacy_staff`

**Response 200**
```json
{
  "success": true,
  "data": {
    "reports": [
      { "report_id": "GSTR-1-DRAFT",   "name": "GSTR-1 Draft",           "group": "GST",         "is_favorite": true },
      { "report_id": "GSTR-3B-DRAFT",  "name": "GSTR-3B Summary",        "group": "GST",         "is_favorite": true },
      { "report_id": "SALES-REGISTER", "name": "Sales Register",          "group": "TRANSACTION", "is_favorite": false },
      { "report_id": "PURCHASE-REG",   "name": "Purchase Register",       "group": "TRANSACTION", "is_favorite": false },
      { "report_id": "STOCK-SUMMARY",  "name": "Stock Summary",           "group": "ITEM",        "is_favorite": false },
      { "report_id": "DEAD-STOCK",     "name": "Dead Stock Report",       "group": "ITEM",        "is_favorite": false },
      { "report_id": "PARTY-LEDGER",   "name": "Party Ledger",            "group": "PARTY",       "is_favorite": false },
      { "report_id": "DAYBOOK",        "name": "Day Book",                "group": "SUMMARY",     "is_favorite": false },
      { "report_id": "PL-STATEMENT",   "name": "Profit & Loss Statement", "group": "SUMMARY",     "is_favorite": true }
    ]
  },
  "meta": {}
}
```

---

### GET /api/v1/pharmacy/analytics/reports/:report_id

Run a pre-built report and return its data or export URL.

**Auth**: Bearer JWT - `pharmacy_owner`, `pharmacy_staff`

**Path Parameters**

| Parameter | Type | Description |
|-----------|------|-------------|
| report_id | string | Report ID from catalogue |

**Query Parameters**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| period | string | Yes | Period selector |
| date_from | string | No | Required when CUSTOM |
| date_to | string | No | Required when CUSTOM |
| export | string | No | `excel` or `pdf` |

**Response 200 (inline)**
```json
{
  "success": true,
  "data": {
    "report_id": "GSTR-1-DRAFT",
    "name": "GSTR-1 Draft",
    "period_from": "2026-07-01",
    "period_to": "2026-07-31",
    "columns": ["invoice_number", "customer_gstin", "taxable_value", "cgst", "sgst", "igst", "total"],
    "rows": [
      ["INV-2026-07-001", "29ABCDE1234F1Z5", 48000, 1200, 1200, 0, 50400]
    ],
    "totals": {
      "taxable_value": 2640000,
      "cgst": 52000,
      "sgst": 52000,
      "igst": 0,
      "total": 2744000
    },
    "export_url": null
  },
  "meta": {}
}
```

When `?export=excel` or `?export=pdf` and rows > 500, `export_url` is an S3 pre-signed URL; otherwise inline.

**Error Table**

| HTTP Code | Error Code | Condition |
|-----------|-----------|-----------|
| 404 | REPORT_NOT_FOUND | report_id does not exist |
| 403 | PLAN_UPGRADE_REQUIRED | Pharmacy on Free/Starter |

---

## Data Models

### pharmacy_analytics_daily

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| pharmacy_id | UUID | FK ? pharmacies |
| snapshot_date | DATE | |
| channel | VARCHAR(10) | ONLINE, COUNTER |
| revenue_paise | BIGINT | |
| cogs_paise | BIGINT | |
| gross_profit_paise | BIGINT | |
| units_sold | INTEGER | |
| output_gst_paise | BIGINT | |
| input_itc_paise | BIGINT | |
| orders_count | INTEGER | |

### pharmacy_report_favorites

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| pharmacy_id | UUID | FK ? pharmacies |
| report_id | VARCHAR(50) | Report identifier |
| created_at | TIMESTAMPTZ | |

## Acceptance Criteria

1. **AC-001**: GET /overview returns 403 with `error.code = PLAN_UPGRADE_REQUIRED` for a pharmacy on the Free plan.
2. **AC-002**: GET /accounts-gst returns GST slab breakdown with 5%, 12%, and 18% slabs; `output_gst` across all slabs matches `gst_liability.output_gst_paise` total.
3. **AC-003**: GET /products with `?dead_stock_only=true` returns only products with `dead_stock_flag: true`.
4. **AC-004**: GET /products sorts by revenue descending by default; changing `sort=margin_pct` re-orders results accordingly.
5. **AC-005**: GET /reports/:report_id with `?export=excel` for a report with > 500 rows returns `export_url` (async S3 link), not inline data.
6. **AC-006**: GET /reports-catalogue returns `is_favorite: true` for reports previously favorited by the pharmacy owner.
7. **AC-007**: GET /accounts-gst day book rows are chronological; `balance_paise` is a running cumulative balance throughout the period.
8. **AC-008**: A `pharmacy_staff` user can read analytics but cannot toggle report favorites (returns 403 on the PATCH favorites endpoint if added in future).
9. **AC-009**: GET /overview with period=FY computes from April 1 of the current financial year to today's date.

## Dependencies

| Dependency | Type | Notes |
|-----------|------|-------|
| EPIC-001 Order Management | Data source | Online sales data |
| EPIC-006 Pharmacy ERP | Data source | Counter sales, purchases, COGS |
| EPIC-012 CRM Subscriptions | Gate | Plan tier enforcement |
| AWS S3 | Storage | Export file uploads |
| GSTN / GST calculation engine | Internal | Slab-level GST computation |

## Notes

- The `accounts-gst` endpoint is the most complex in this story and powers the pharmacy's GST filing preparation. Accuracy is critical; any discrepancy must be surfaced as a `data_warning` flag in the response.
- GSTR-1 and GSTR-3B reports are draft exports; they are not submitted to the GSTN portal through this system (pharmacies use their CA or the GSTN portal directly).
- Day book "balance" resets to zero at the start of each fiscal year.
