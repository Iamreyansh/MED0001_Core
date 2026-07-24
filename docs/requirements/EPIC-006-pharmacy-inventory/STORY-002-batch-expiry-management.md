# STORY-002: Batch & Expiry Management - FEFO Tracking and Expiry Alerts

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-002 |
| **Epic** | EPIC-006 - Pharmacy Inventory |
| **Priority** | P0 |
| **Complexity** | L |
| **Status** | Draft |

---

## Overview

This story defines the batch-level inventory API for the Namma MedMate Pharmacy Dashboard. Every stock-keeping unit is tracked at the batch granularity - with batch number, expiry date, purchase price, and current quantity. The system enforces FEFO (First Expiry First Out) dispensing: when the POS creates a sale, it auto-selects the batch whose `expiry_date` is earliest. This story also provides expiry alert screens and a full expiry report for compliance and write-off workflows. Batch quantity adjustments are logged with an audit reason code.

---

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| `pharmacy_owner` | Full read + write | Create, adjust, and write off batches |
| `pharmacy_staff` | Read + limited write | View batches; record adjustments with reason; cannot write off |
| `admin_super` | Read-only | Cross-pharmacy compliance view |
| `admin_compliance` | Read-only | Expiry audit access |
| `customer` | No access | Not applicable |

---

## Business Rules

1. **FEFO dispensing.** The POS auto-selects the batch with the earliest `expiry_date` when adding a product to cart. Pharmacists may manually override to a later batch, but FEFO is the default and must be enforced in auto-add flows.
2. **Batch uniqueness per pharmacy.** `batch_number + pharmacy_id` must be unique. Attempting to add a batch with an existing batch number for the same pharmacy-product will top up the existing batch quantity instead of creating a duplicate.
3. **Expired batches hidden from POS.** Batches where `expiry_date < today` are automatically excluded from POS suggestions and barcode-scan results. They remain visible in the inventory detail for write-off purposes.
4. **Write-off deducts stock.** `DELETE /batches/:batch_id` sets `quantity_current = 0`, records `write_off_reason`, logs a stock movement event, and marks the batch `is_active = false`. The write-off is irreversible.
5. **Batch quantity cannot go negative.** Any adjustment that would result in `quantity_current < 0` is rejected with `INSUFFICIENT_BATCH_QUANTITY`. The adjustment value must be validated before applying.
6. **Zero-quantity batches are archived.** When `quantity_current` reaches 0 (via adjustment or sale), `is_active` is set to `false`. Archived batches are excluded from `total_stock_units` computation and from POS suggestions.
7. **Expiry alert grouping.** The expiry alert API groups medicines into three buckets: `< 1 month`, `1-2 months`, and `2-4 months` based on days remaining from today.
8. **Free-quantity batches.** When a batch is added with `free_quantity > 0` (distributor scheme), `quantity_received = quantity + free_quantity` and `purchase_price_per_unit` applies only to the paid quantity for cost calculation purposes.
9. **Audit trail.** Every batch quantity adjustment records: `staff_id`, `adjustment_value`, `reason`, `timestamp`, `before_qty`, `after_qty`. These are append-only and cannot be deleted.

---

## API Endpoints

### 1. List Batches for a Product (FEFO Order)

```
GET /api/v1/pharmacy/inventory/:product_id/batches
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 120 req/min

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `include_inactive` | boolean | `false` | Include archived/zero-quantity batches |

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "product_id": "uuid",
    "product_name": "Paracetamol 500mg Tab",
    "batches": [
      {
        "id": "uuid",
        "batch_number": "BN24001",
        "expiry_date": "2026-10-31",
        "manufactured_date": "2024-10-01",
        "quantity_current": 150,
        "quantity_received": 200,
        "purchase_price_per_unit": 14.00,
        "mrp_per_unit": 22.50,
        "is_active": true,
        "days_to_expiry": 99,
        "expiry_status": "EXPIRING_SOON",
        "received_date": "2026-01-15T10:00:00Z"
      },
      {
        "id": "uuid",
        "batch_number": "BN24008",
        "expiry_date": "2027-03-31",
        "manufactured_date": "2025-03-01",
        "quantity_current": 300,
        "quantity_received": 300,
        "purchase_price_per_unit": 13.50,
        "mrp_per_unit": 22.50,
        "is_active": true,
        "days_to_expiry": 615,
        "expiry_status": "OK",
        "received_date": "2026-05-20T10:00:00Z"
      }
    ],
    "total_active_units": 450
  }
}
```

---

### 2. Manually Add a Batch

```
POST /api/v1/pharmacy/inventory/:product_id/batches
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 30 req/min

**Request Body (application/json):**

```json
{
  "batch_number": "string - required, max 50 chars",
  "expiry_date": "date string YYYY-MM-DD - required",
  "manufactured_date": "date string YYYY-MM-DD - optional",
  "quantity": "integer > 0 - required",
  "free_quantity": "integer ? 0 - optional, default 0",
  "purchase_price_per_unit": "number > 0 - required",
  "mrp_per_unit": "number > 0 - required"
}
```

**Success Response - 201 Created:**

```json
{
  "success": true,
  "data": {
    "id": "uuid",
    "batch_number": "BN25100",
    "expiry_date": "2027-06-30",
    "quantity_received": 300,
    "quantity_current": 300,
    "purchase_price_per_unit": 13.00,
    "mrp_per_unit": 22.50,
    "is_active": true,
    "created_at": "2026-07-24T08:00:00Z"
  }
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `BATCH_ALREADY_EXISTS` | `batch_number` already active for this product+pharmacy; quantity topped up instead |
| 400 | `EXPIRY_DATE_IN_PAST` | `expiry_date` is before today |
| 400 | `INVALID_MRP` | `mrp_per_unit ? 0` |
| 404 | `PRODUCT_NOT_FOUND` | `product_id` not found for this pharmacy |

---

### 3. Adjust Batch Quantity

```
PATCH /api/v1/pharmacy/inventory/:product_id/batches/:batch_id
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 30 req/min

**Request Body (application/json):**

```json
{
  "adjustment": "integer, positive or negative (e.g. -5 or +10) - required",
  "reason": "DAMAGE | RETURN | AUDIT_CORRECTION | EXPIRY_WRITE_OFF - required"
}
```

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "batch_id": "uuid",
    "batch_number": "BN24001",
    "before_qty": 150,
    "adjustment": -5,
    "after_qty": 145,
    "reason": "DAMAGE",
    "adjusted_by": "staff_uuid",
    "adjusted_at": "2026-07-24T08:10:00Z"
  }
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `INSUFFICIENT_BATCH_QUANTITY` | Adjustment would result in `quantity_current < 0` |
| 400 | `BATCH_INACTIVE` | Batch is already archived (`is_active = false`) |
| 400 | `MISSING_REASON` | `reason` field not provided |
| 404 | `BATCH_NOT_FOUND` | `batch_id` not found for this product+pharmacy |

---

### 4. Write Off an Expired Batch

```
DELETE /api/v1/pharmacy/inventory/:product_id/batches/:batch_id
```

**Authentication:** Bearer JWT - `pharmacy_owner`
**Rate Limit:** 10 req/min

**Request Body (application/json):**

```json
{
  "write_off_reason": "EXPIRED | DAMAGED | REGULATORY - required",
  "notes": "string max 500 - optional"
}
```

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "batch_id": "uuid",
    "batch_number": "BN24001",
    "units_written_off": 150,
    "value_written_off": 2100.00,
    "is_active": false,
    "written_off_at": "2026-07-24T08:15:00Z"
  }
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `BATCH_ALREADY_INACTIVE` | Batch already written off |
| 403 | `STAFF_CANNOT_WRITE_OFF` | Only `pharmacy_owner` may write off batches |
| 404 | `BATCH_NOT_FOUND` | Batch not found |

---

### 5. Expiry Alerts (Dashboard Panel)

```
GET /api/v1/pharmacy/inventory/expiry-alerts
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 30 req/min

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "summary": {
      "total_expiring_products": 9,
      "total_expiring_units": 420,
      "total_value_at_risk": 12600.00
    },
    "groups": [
      {
        "bucket": "UNDER_1_MONTH",
        "label": "Expiring in < 1 month",
        "product_count": 2,
        "units": 60,
        "value_at_risk": 1800.00,
        "items": [
          {
            "product_id": "uuid",
            "product_name": "Amoxicillin 250mg Cap",
            "batch_number": "AM23010",
            "expiry_date": "2026-08-15",
            "days_to_expiry": 22,
            "quantity_current": 30,
            "purchase_price_per_unit": 8.50,
            "value_at_risk": 255.00
          }
        ]
      },
      {
        "bucket": "1_TO_2_MONTHS",
        "label": "Expiring in 1-2 months",
        "product_count": 3,
        "units": 180,
        "value_at_risk": 5400.00,
        "items": []
      },
      {
        "bucket": "2_TO_4_MONTHS",
        "label": "Expiring in 2-4 months",
        "product_count": 4,
        "units": 180,
        "value_at_risk": 5400.00,
        "items": []
      }
    ]
  }
}
```

---

### 6. Full Expiry Report (Export)

```
GET /api/v1/pharmacy/inventory/expiry-report
```

**Authentication:** Bearer JWT - `pharmacy_owner`
**Rate Limit:** 10 req/min

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `within_months` | integer | `4` | Report scope: batches expiring within N months |
| `export` | enum | `JSON` | `JSON \| EXCEL \| PDF` |

**Success Response - 200 OK (JSON):**

```json
{
  "success": true,
  "data": {
    "report_date": "2026-07-24",
    "scope_months": 4,
    "total_batches": 18,
    "total_value_at_risk": 42000.00,
    "batches": [
      {
        "product_name": "Amoxicillin 250mg Cap",
        "batch_number": "AM23010",
        "expiry_date": "2026-08-15",
        "days_to_expiry": 22,
        "quantity_current": 30,
        "purchase_price_per_unit": 8.50,
        "value_at_risk": 255.00,
        "rack_location": "B2-04"
      }
    ]
  }
}
```

---

## Data Models

### ProductBatch

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID | PK, auto-generated | Unique batch record |
| `product_id` | UUID | FK ? PharmacyProduct, NOT NULL | Parent product |
| `pharmacy_id` | UUID | FK ? Pharmacy, NOT NULL | Owning pharmacy (denormalized for query performance) |
| `batch_number` | VARCHAR(50) | NOT NULL | Manufacturer batch/lot number |
| `expiry_date` | DATE | NOT NULL | Batch expiry date |
| `manufactured_date` | DATE | nullable | Manufacturing date |
| `quantity_received` | INTEGER | > 0, NOT NULL | Original quantity at GRN |
| `quantity_current` | INTEGER | ? 0, NOT NULL | Current remaining quantity |
| `purchase_price_per_unit` | NUMERIC(10,2) | > 0, NOT NULL | Cost per unit (PTR) |
| `mrp_per_unit` | NUMERIC(10,2) | > 0, NOT NULL | MRP per unit |
| `is_active` | BOOLEAN | NOT NULL, default true | False when written off or fully consumed |
| `write_off_reason` | ENUM | nullable | EXPIRED / DAMAGED / REGULATORY |
| `write_off_notes` | TEXT | nullable | Free-text write-off notes |
| `grn_item_id` | UUID | FK ? PurchaseGRNItem, nullable | Source GRN line (if from purchase) |
| `created_at` | TIMESTAMPTZ | NOT NULL, default now() | Batch creation time |
| `updated_at` | TIMESTAMPTZ | NOT NULL, auto-update | Last update timestamp |

### BatchAdjustmentLog

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID | PK, auto-generated | Log entry ID |
| `batch_id` | UUID | FK ? ProductBatch, NOT NULL | Batch adjusted |
| `pharmacy_id` | UUID | NOT NULL | Pharmacy context |
| `staff_id` | UUID | FK ? Staff, NOT NULL | Who made the adjustment |
| `adjustment` | INTEGER | NOT NULL | Positive (increase) or negative (decrease) value |
| `reason` | ENUM | NOT NULL | DAMAGE / RETURN / AUDIT_CORRECTION / EXPIRY_WRITE_OFF |
| `before_qty` | INTEGER | NOT NULL | Quantity before adjustment |
| `after_qty` | INTEGER | NOT NULL | Quantity after adjustment |
| `created_at` | TIMESTAMPTZ | NOT NULL, default now() | Log timestamp |

---

## Acceptance Criteria

- [ ] Given a product with 2 batches, when the POS adds the product to a cart, then the batch with the earlier `expiry_date` is auto-selected.
- [ ] Given `PATCH /batches/:batch_id` with `adjustment = -200` when `quantity_current = 150`, then a 400 `INSUFFICIENT_BATCH_QUANTITY` error is returned and no mutation occurs.
- [ ] Given a batch with `expiry_date < today`, when the POS product search is executed, then that batch does not appear in the barcode/search suggestions.
- [ ] Given `DELETE /batches/:batch_id` called by a `pharmacy_staff` role, then a 403 `STAFF_CANNOT_WRITE_OFF` error is returned.
- [ ] Given `GET /expiry-alerts`, then each product appears in exactly one bucket based on its `earliest_expiry` date, with correct `value_at_risk = quantity_current - purchase_price_per_unit`.
- [ ] Given a batch with `free_quantity = 20` and `quantity = 100`, when the batch is created via POST, then `quantity_received = 120` and cost computation uses only the 100 paid units.
- [ ] Given `DELETE /batches/:batch_id` (write-off), then `quantity_current` becomes 0, `is_active` becomes false, and a `BatchAdjustmentLog` entry with `reason = EXPIRY_WRITE_OFF` is created.
- [ ] Given `GET /expiry-report?export=PDF`, then a downloadable PDF report is returned containing all batches expiring within 4 months.

---

## Dependencies

- **EPIC-006 / STORY-001 (Inventory Management):** `ProductBatch` records update computed fields on `PharmacyProduct`.
- **EPIC-006 / STORY-004 (Purchase/GRN):** Batches are primarily created when a GRN is finalized.
- **EPIC-007 / STORY-001 (POS):** POS uses FEFO batch selection logic defined here.
- **EPIC-008 / Reports:** Expiry value-at-risk feeds into the pharmacy P&L report.

---

## Notes

- Batch adjustments must not be soft-deletable. `BatchAdjustmentLog` is immutable once written.
- When `BATCH_ALREADY_EXISTS` on manual add, the system silently tops up quantity and returns the existing batch record with a `201` and a `"topped_up": true` flag.
- The expiry-alert grouping buckets are computed at query time (not stored) based on `CURRENT_DATE`.
- Consider a nightly cron job to auto-archive batches that reach `quantity_current = 0` via sales without explicit write-off.
