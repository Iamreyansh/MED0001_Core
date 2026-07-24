# STORY-004: Purchase / GRN Management - Distributor Invoice Entry & Goods Received Notes

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-004 |
| **Epic** | EPIC-006 - Pharmacy Inventory |
| **Priority** | P0 |
| **Complexity** | XL |
| **Status** | Draft |

---

## Overview

This story covers the full Goods Received Note (GRN) workflow - the primary mechanism through which new stock enters the pharmacy's inventory. A pharmacist can enter a distributor invoice line by line, specifying batch details, MRP, purchase price, GST, and free-goods schemes. Once a GRN is finalized with "Save & Stock," the system creates or tops up `ProductBatch` records and updates `PharmacyProduct` computed fields. A bulk CSV import accelerates entry for large distributor invoices. GRN data also feeds input GST credit for GSTR-2A reconciliation.

---

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| `pharmacy_owner` | Full read + write | Create, edit, finalize GRNs; access all purchase records |
| `pharmacy_staff` | Create + edit DRAFT | Can create GRNs and add items; cannot finalize (STOCKED) |
| `admin_finance` | Read-only | Cross-pharmacy purchase data for financial reporting |
| `customer` | No access | Not applicable |

---

## Business Rules

1. **Products are created via GRN.** When a GRN item references a product that does not yet exist in the pharmacy's inventory, the system auto-creates a `PharmacyProduct` record using the line item data. The new product is set to `is_online_visible = false` by default.
2. **GRN status lifecycle.** A GRN progresses through: `DRAFT ? SAVED ? STOCKED`. `DRAFT` allows free editing. `SAVED` locks the header (invoice number, date, distributor) but allows item edits. `STOCKED` is immutable - no edits allowed after stocking.
3. **Duplicate invoice rejection.** An invoice number from the same distributor cannot be entered twice. The combination `(pharmacy_id, distributor_id, invoice_number)` must be unique. Soft-deleted distributors are still checked.
4. **Save & Stock triggers batch updates.** Finalizing a GRN (POST `/save-and-stock`) creates a new `ProductBatch` record for each line item, or tops up an existing active batch if the batch number already exists for that product. MRP and cost price on the batch are set to the GRN line values.
5. **Free quantity handling.** If `free_quantity > 0`, the total batch quantity received = `quantity + free_quantity`, but the cost of free units is not included in `purchase_price_per_unit` calculations. Input GST credit applies only to the taxable amount (paid units - purchase_price - gst_pct).
6. **Input GST credit tracking.** Each GRN item records `gst_amount = taxable_amount - gst_pct / 100`. The total input GST credit for a GRN is the sum of all line-item GST amounts. This figure is surfaced in the GST summary KPI and GSTR-2A export.
7. **CSV import product matching.** The CSV import attempts to match rows to existing `PharmacyProduct` records by `name` (case-insensitive, trimmed) and `manufacturer`. Unmatched rows are flagged for manual review in the import preview; if confirmed, they are auto-created as new products.
8. **GRN line item product search.** When entering a GRN line item, the `product_search_query` matches existing pharmacy products first. If no match, the user can confirm creation of a new product. The auto-complete response returns both existing and new-product options.

---

## API Endpoints

### 1. List GRNs

```
GET /api/v1/pharmacy/purchases
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 60 req/min

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `status` | enum | - | `DRAFT \| SAVED \| STOCKED` |
| `distributor_id` | UUID | - | Filter by distributor |
| `from_date` | date | - | Invoice date range start (YYYY-MM-DD) |
| `to_date` | date | - | Invoice date range end |
| `q` | string | - | Search by invoice number |
| `page` | integer | `1` | Page number |
| `limit` | integer | `20` | Items per page |

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "kpi": {
      "purchases_this_month": 18,
      "input_gst_credit_this_month": 24500.00,
      "total_grns": 124
    },
    "grns": [
      {
        "grn_id": "uuid",
        "distributor_name": "Medico Pharma Distributors",
        "invoice_number": "MED-2026-04521",
        "invoice_date": "2026-07-22",
        "line_count": 24,
        "taxable_amount": 48000.00,
        "gst_amount": 5760.00,
        "total": 53760.00,
        "status": "STOCKED",
        "created_at": "2026-07-22T16:00:00Z"
      }
    ]
  },
  "meta": {
    "page": 1,
    "limit": 20,
    "total": 124
  }
}
```

---

### 2. Create GRN Header

```
POST /api/v1/pharmacy/purchases
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 30 req/min

**Request Body (application/json):**

```json
{
  "distributor_id": "UUID - required",
  "invoice_number": "string max 100 - required",
  "invoice_date": "date YYYY-MM-DD - required"
}
```

**Success Response - 201 Created:**

```json
{
  "success": true,
  "data": {
    "grn_id": "uuid",
    "distributor_id": "uuid",
    "distributor_name": "Medico Pharma Distributors",
    "invoice_number": "MED-2026-04521",
    "invoice_date": "2026-07-22",
    "status": "DRAFT",
    "line_count": 0,
    "created_at": "2026-07-24T10:00:00Z"
  }
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `DUPLICATE_INVOICE_NUMBER` | Same distributor + invoice_number already exists for this pharmacy |
| 400 | `FUTURE_INVOICE_DATE` | `invoice_date` is in the future |
| 404 | `DISTRIBUTOR_NOT_FOUND` | `distributor_id` not found for this pharmacy |

---

### 3. Add Line Item to GRN

```
POST /api/v1/pharmacy/purchases/:grn_id/items
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 60 req/min

**Request Body (application/json):**

```json
{
  "product_search_query": "string - optional if product_id provided",
  "product_id": "UUID - optional if search_query provided",
  "create_new_product": "boolean - optional, default false; if true creates new product from below fields",
  "new_product_name": "string - required if create_new_product = true",
  "new_product_manufacturer": "string - optional",
  "new_product_pack_size": "integer - required if create_new_product = true",
  "new_product_form": "TABLET | SYRUP | CAPSULE | DROPS | INJECTION | OTHER - required if create_new_product = true",
  "batch_number": "string max 50 - required",
  "expiry_date": "date YYYY-MM-DD - required",
  "manufactured_date": "date YYYY-MM-DD - optional",
  "quantity": "integer > 0 - required",
  "free_quantity": "integer ? 0 - optional, default 0",
  "purchase_price_per_unit": "number > 0 - required (PTR)",
  "mrp_per_unit": "number > 0 - required",
  "gst_pct": "number: 0 | 5 | 12 | 18 | 28 - required"
}
```

**Success Response - 201 Created:**

```json
{
  "success": true,
  "data": {
    "item_id": "uuid",
    "grn_id": "uuid",
    "product_id": "uuid",
    "product_name": "Paracetamol 500mg Tab",
    "is_new_product": false,
    "batch_number": "BN25100",
    "expiry_date": "2027-06-30",
    "quantity": 200,
    "free_quantity": 0,
    "quantity_total": 200,
    "purchase_price_per_unit": 13.00,
    "mrp_per_unit": 22.50,
    "gst_pct": 12,
    "taxable_amount": 2600.00,
    "gst_amount": 312.00,
    "line_total": 2912.00
  }
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `GRN_ALREADY_STOCKED` | GRN `status = STOCKED`, cannot add items |
| 400 | `EXPIRY_DATE_IN_PAST` | `expiry_date` is before today |
| 400 | `INVALID_GST_PCT` | GST percentage not in allowed slabs |
| 404 | `GRN_NOT_FOUND` | `grn_id` not found for this pharmacy |

---

### 4. Edit a GRN Line Item

```
PATCH /api/v1/pharmacy/purchases/:grn_id/items/:item_id
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 30 req/min

**Request Body (application/json):**

```json
{
  "quantity": "integer > 0 - optional",
  "free_quantity": "integer ? 0 - optional",
  "purchase_price_per_unit": "number > 0 - optional",
  "mrp_per_unit": "number > 0 - optional",
  "expiry_date": "date YYYY-MM-DD - optional",
  "gst_pct": "number - optional"
}
```

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "item_id": "uuid",
    "quantity": 250,
    "taxable_amount": 3250.00,
    "gst_amount": 390.00,
    "line_total": 3640.00,
    "updated_at": "2026-07-24T10:20:00Z"
  }
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `GRN_ALREADY_STOCKED` | GRN is finalized |
| 404 | `ITEM_NOT_FOUND` | Item not found in this GRN |

---

### 5. Remove a GRN Line Item

```
DELETE /api/v1/pharmacy/purchases/:grn_id/items/:item_id
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 30 req/min

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "item_id": "uuid",
    "deleted": true
  }
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `GRN_ALREADY_STOCKED` | GRN is finalized |
| 404 | `ITEM_NOT_FOUND` | Item not found |

---

### 6. Save & Stock (Finalize GRN)

```
POST /api/v1/pharmacy/purchases/:grn_id/save-and-stock
```

**Authentication:** Bearer JWT - `pharmacy_owner`
**Rate Limit:** 10 req/min

**Request Body:** None required.

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "grn_id": "uuid",
    "status": "STOCKED",
    "stocked_at": "2026-07-24T10:30:00Z",
    "line_count": 24,
    "new_products_created": 2,
    "batches_created": 22,
    "batches_topped_up": 2,
    "total_units_added": 3600,
    "total_value_at_cost": 53760.00,
    "total_input_gst_credit": 5760.00,
    "updated_stock_summary": [
      {
        "product_id": "uuid",
        "product_name": "Paracetamol 500mg Tab",
        "units_added": 200,
        "new_total_stock": 650
      }
    ]
  }
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `GRN_EMPTY` | GRN has no line items |
| 400 | `GRN_ALREADY_STOCKED` | Already finalized |
| 403 | `STAFF_CANNOT_STOCK` | `pharmacy_staff` cannot finalize GRN |

---

### 7. Bulk Import via CSV

```
POST /api/v1/pharmacy/purchases/import-csv
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 5 req/min

**Request:** `multipart/form-data`

| Field | Type | Description |
|-------|------|-------------|
| `csv_file` | file | CSV file (max 5MB) |
| `distributor_id` | UUID | Distributor for this invoice |
| `invoice_number` | string | Invoice number |
| `invoice_date` | date | Invoice date |

**Expected CSV columns:** `product_name, manufacturer, batch_number, expiry_date, quantity, free_quantity, purchase_price, mrp, gst_pct`

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "grn_id": "uuid",
    "total_rows": 30,
    "matched_rows": 26,
    "unmatched_rows": 4,
    "status": "DRAFT",
    "preview_items": [],
    "unmatched_items": [
      {
        "row_number": 12,
        "raw_data": { "product_name": "SomeUnknownMed 100mg", "manufacturer": "XYZ Labs" },
        "suggested_action": "CREATE_NEW"
      }
    ]
  }
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `INVALID_CSV_FORMAT` | CSV missing required columns |
| 400 | `FILE_TOO_LARGE` | File exceeds 5MB |
| 400 | `DUPLICATE_INVOICE_NUMBER` | Invoice already exists |

---

### 8. Get GRN Detail

```
GET /api/v1/pharmacy/purchases/:grn_id
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 60 req/min

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "grn_id": "uuid",
    "distributor": { "id": "uuid", "firm_name": "Medico Pharma" },
    "invoice_number": "MED-2026-04521",
    "invoice_date": "2026-07-22",
    "status": "STOCKED",
    "items": [
      {
        "item_id": "uuid",
        "product_id": "uuid",
        "product_name": "Paracetamol 500mg Tab",
        "batch_number": "BN25100",
        "expiry_date": "2027-06-30",
        "quantity": 200,
        "free_quantity": 0,
        "purchase_price_per_unit": 13.00,
        "mrp_per_unit": 22.50,
        "gst_pct": 12,
        "taxable_amount": 2600.00,
        "gst_amount": 312.00,
        "line_total": 2912.00
      }
    ],
    "totals": {
      "taxable_amount": 48000.00,
      "gst_amount": 5760.00,
      "grand_total": 53760.00,
      "input_gst_credit": 5760.00
    }
  }
}
```

---

## Data Models

### PurchaseGRN

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID | PK | Unique GRN ID |
| `pharmacy_id` | UUID | FK ? Pharmacy, NOT NULL | Owning pharmacy |
| `distributor_id` | UUID | FK ? Distributor, NOT NULL | Supplying distributor |
| `invoice_number` | VARCHAR(100) | NOT NULL | Distributor invoice reference |
| `invoice_date` | DATE | NOT NULL | Date on the distributor invoice |
| `status` | ENUM | NOT NULL, default DRAFT | DRAFT / SAVED / STOCKED |
| `stocked_at` | TIMESTAMPTZ | nullable | Timestamp when finalized |
| `stocked_by` | UUID | FK ? Staff, nullable | Staff who finalized |
| `created_by` | UUID | FK ? Staff, NOT NULL | Staff who created the GRN |
| `created_at` | TIMESTAMPTZ | NOT NULL, default now() | Creation timestamp |
| `updated_at` | TIMESTAMPTZ | NOT NULL, auto-update | Last update |

### PurchaseGRNItem

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID | PK | Unique line item ID |
| `grn_id` | UUID | FK ? PurchaseGRN, NOT NULL | Parent GRN |
| `pharmacy_id` | UUID | NOT NULL | Denormalized for query performance |
| `product_id` | UUID | FK ? PharmacyProduct, NOT NULL | Product received |
| `batch_number` | VARCHAR(50) | NOT NULL | Batch/lot number |
| `expiry_date` | DATE | NOT NULL | Batch expiry |
| `manufactured_date` | DATE | nullable | Manufacturing date |
| `quantity` | INTEGER | > 0, NOT NULL | Paid quantity |
| `free_quantity` | INTEGER | ? 0, NOT NULL, default 0 | Free goods quantity |
| `purchase_price_per_unit` | NUMERIC(10,2) | > 0, NOT NULL | PTR per unit |
| `mrp_per_unit` | NUMERIC(10,2) | > 0, NOT NULL | MRP per unit |
| `gst_pct` | NUMERIC(5,2) | NOT NULL | GST slab for this item |
| `taxable_amount` | NUMERIC(12,2) | computed | quantity - purchase_price_per_unit |
| `gst_amount` | NUMERIC(12,2) | computed | taxable_amount - gst_pct / 100 |
| `line_total` | NUMERIC(12,2) | computed | taxable_amount + gst_amount |
| `created_at` | TIMESTAMPTZ | NOT NULL | Line item creation time |

---

## Acceptance Criteria

- [ ] Given `POST /purchases` with a `distributor_id` and `invoice_number`, then a GRN in `DRAFT` status is created and returned.
- [ ] Given a second `POST /purchases` with the same `distributor_id` and `invoice_number` for the same pharmacy, then a 400 `DUPLICATE_INVOICE_NUMBER` error is returned.
- [ ] Given `POST /purchases/:grn_id/save-and-stock` on a GRN with 10 items, then 10 `ProductBatch` records are created and all parent `PharmacyProduct.total_stock_units` values are updated.
- [ ] Given a GRN item with `free_quantity = 20` and `quantity = 100`, when the GRN is finalized, then the created batch has `quantity_received = 120` and `taxable_amount` is calculated on 100 units only.
- [ ] Given `POST /purchases/:grn_id/save-and-stock` on an already-STOCKED GRN, then a 400 `GRN_ALREADY_STOCKED` error is returned.
- [ ] Given a `pharmacy_staff` JWT calling `POST /purchases/:grn_id/save-and-stock`, then a 403 `STAFF_CANNOT_STOCK` is returned.
- [ ] Given a CSV import with 30 rows where 4 have unrecognized product names, then a DRAFT GRN is created with 26 matched items and 4 unmatched items returned for review.
- [ ] Given `GET /purchases` with `status=STOCKED`, then only finalized GRNs are returned, each with correct `taxable_amount`, `gst_amount`, and `total` fields.

---

## Dependencies

- **EPIC-006 / STORY-002 (Batch Management):** `save-and-stock` creates `ProductBatch` records using the logic defined in STORY-002.
- **EPIC-006 / STORY-005 (Distributors):** `distributor_id` FK references the distributor directory.
- **EPIC-008 / Reports:** Input GST credit aggregate feeds GSTR-2A report.

---

## Notes

- CSV import uses a two-step flow: Step 1 is the parse/preview (`import-csv` returns preview + unmatched), Step 2 is confirmation (separate `POST /purchases/:grn_id/confirm-import`). Unmatched rows can be manually resolved in the UI before stocking.
- The `save-and-stock` operation is a database transaction. If any batch update fails, the entire operation is rolled back and the GRN remains in its prior state.
- MRP on `PharmacyProduct` is updated to the latest GRN line `mrp_per_unit` on finalization.
