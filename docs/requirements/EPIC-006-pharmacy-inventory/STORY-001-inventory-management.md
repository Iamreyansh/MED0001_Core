# STORY-001: Inventory Management - Stock Master, Product CRUD & Visibility Controls

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-001 |
| **Epic** | EPIC-006 - Pharmacy Inventory |
| **Priority** | P0 |
| **Complexity** | L |
| **Status** | Draft |

---

## Overview

This story defines the core product stock-master API for the Pharmacy Dashboard. It provides pharmacists with a paginated, searchable, filterable inventory list, a suite of KPI summary cards, and per-product detail and edit endpoints. Products are created indirectly through the Purchase/GRN flow (STORY-004) and are updated here. The story also controls two critical visibility flags: `is_online_visible` (whether the product shows on the customer app) and `is_loose_selling_enabled` (sold per tablet/unit rather than full strip/pack).

---

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| `pharmacy_owner` | Full read + write | Can view, edit all product settings and master info |
| `pharmacy_staff` | Read + limited write | Can view inventory and update `rack_location_code`; cannot change pricing or visibility flags |
| `admin_super` | Read-only | Compliance/support visibility across pharmacies |
| `admin_compliance` | Read-only | Audit access to product details |
| `customer` | No access | Not applicable for this endpoint |

---

## Business Rules

1. **Products are created via Purchases only.** A `PharmacyProduct` record is created when a GRN is saved-and-stocked (STORY-004). Direct product creation via this endpoint is not permitted; the PATCH endpoints only update settings on existing records.
2. **Stock is auto-maintained.** `total_stock_units` is a computed sum of all active `ProductBatch.quantity_current` for that product. It is never set manually and is recalculated on every batch mutation.
3. **Low-stock flag.** A product is flagged `LOW_STOCK` when `total_stock_units ? reorder_level`. If `reorder_level` is 0, low-stock alert is suppressed.
4. **Expiring flag.** A product is flagged `EXPIRING` when its `earliest_expiry` date falls within the next 4 calendar months from today's date.
5. **Dead-stock flag.** A product is flagged as dead stock when no sales or dispensing event has been recorded against any of its batches in the past 90 days.
6. **`is_online_visible` controls customer app.** When `false`, the product is hidden from all customer-facing search and store listings. Default is `false` for newly created products; the pharmacist must explicitly enable online visibility.
7. **`is_loose_selling_enabled` enables per-unit sale.** When `true`, the POS allows quantity entry in individual units (tablets/ml) rather than full packs. The `pack_size` and `pack_unit` fields define the conversion factor.
8. **`OUT_OF_STOCK` filter.** Products with `total_stock_units = 0` appear in the `OUT_OF_STOCK` tab. Products with `is_online_visible = true` and `total_stock_units = 0` are automatically hidden from the customer app until restocked.
9. **`UNALLOCATED` filter.** Products with no rack location assigned appear in the `UNALLOCATED` tab.
10. **Plan enforcement.** The inventory module is available on all plans (Free+). The `is_online_visible` flag is functional only on Growth+ plans where the Online Store module is active.

---

## API Endpoints

### 1. List Inventory (Paginated)

```
GET /api/v1/pharmacy/inventory
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 60 req/min

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `tab` | enum | `ALL` | `ALL \| ALERTS \| LOW_STOCK \| EXPIRING \| RX_ONLY \| OUT_OF_STOCK \| UNALLOCATED` |
| `q` | string | - | Full-text search: product name, salt composition, brand, rack code |
| `sort` | enum | `name` | `name \| stock \| value \| expiry` |
| `order` | enum | `asc` | `asc \| desc` |
| `page` | integer | `1` | Page number |
| `limit` | integer | `20` | Items per page (max 100) |
| `export` | enum | - | `EXCEL \| PDF` - triggers file export instead of JSON |
| `category_id` | UUID | - | Filter by product category |

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "products": [
      {
        "id": "uuid",
        "name": "Paracetamol 500mg Tab",
        "manufacturer": "Cipla Ltd",
        "salt_composition": "Paracetamol 500mg",
        "form": "TABLET",
        "pack_size": 15,
        "pack_unit": "tablets",
        "mrp": 22.50,
        "total_stock_units": 450,
        "total_stock_packs": 30,
        "reorder_level": 60,
        "earliest_expiry": "2026-10-31",
        "is_rx_only": false,
        "is_loose_selling_enabled": false,
        "is_online_visible": true,
        "rack_locations": ["A1-03"],
        "flags": ["LOW_STOCK"],
        "cost_value": 6750.00,
        "mrp_value": 10125.00
      }
    ]
  },
  "meta": {
    "page": 1,
    "limit": 20,
    "total": 342,
    "tab_counts": {
      "ALL": 342,
      "ALERTS": 18,
      "LOW_STOCK": 12,
      "EXPIRING": 9,
      "RX_ONLY": 45,
      "OUT_OF_STOCK": 7,
      "UNALLOCATED": 23
    }
  }
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 401 | `UNAUTHORIZED` | Missing or invalid JWT |
| 403 | `FORBIDDEN` | Role does not have inventory access |

---

### 2. Inventory KPI Summary

```
GET /api/v1/pharmacy/inventory/summary
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 30 req/min

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "total_skus": 342,
    "total_units": 48600,
    "stock_value_at_cost": 1245800.00,
    "retail_value_mrp": 2187500.00,
    "low_stock_count": 12,
    "expiring_count": 9,
    "dead_stock_count": 4,
    "out_of_stock_count": 7,
    "unallocated_count": 23,
    "as_of": "2026-07-24T00:00:00Z"
  }
}
```

---

### 3. Product Detail

```
GET /api/v1/pharmacy/inventory/:product_id
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 120 req/min

**Path Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `product_id` | UUID | PharmacyProduct ID |

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "id": "uuid",
    "name": "Paracetamol 500mg Tab",
    "salt_composition": "Paracetamol 500mg",
    "manufacturer": "Cipla Ltd",
    "pack_size": 15,
    "pack_unit": "tablets",
    "category_id": "uuid",
    "category_name": "Analgesics",
    "form": "TABLET",
    "schedule": "OTC",
    "hsn_code": "30049099",
    "gst_pct": 12,
    "mrp": 22.50,
    "is_rx_only": false,
    "is_loose_selling_enabled": false,
    "is_online_visible": true,
    "reorder_level": 60,
    "rack_locations": ["A1-03"],
    "total_stock_units": 450,
    "cost_value": 6750.00,
    "mrp_value": 10125.00,
    "margin_pct": 18.5,
    "units_sold_30d": 120,
    "units_sold_90d": 310,
    "days_of_cover": 11,
    "last_sold_at": "2026-07-23T14:30:00Z",
    "total_batches": 3,
    "earliest_expiry": "2026-10-31",
    "batches": [
      {
        "id": "uuid",
        "batch_number": "BN24001",
        "expiry_date": "2026-10-31",
        "quantity_current": 150,
        "purchase_price_per_unit": 14.00,
        "mrp_per_unit": 22.50,
        "is_active": true
      }
    ],
    "recent_movements": [
      {
        "date": "2026-07-23",
        "type": "SALE",
        "units": -15,
        "reference_id": "INV-2026-07-000042",
        "running_stock": 450
      }
    ]
  }
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 404 | `PRODUCT_NOT_FOUND` | Product ID does not exist for this pharmacy |

---

### 4. Update Product Settings

```
PATCH /api/v1/pharmacy/inventory/:product_id
```

**Authentication:** Bearer JWT - `pharmacy_owner`
**Rate Limit:** 60 req/min

**Request Body (application/json):**

```json
{
  "is_loose_selling_enabled": "boolean - optional",
  "is_online_visible": "boolean - optional",
  "reorder_level": "integer ? 0 - optional",
  "rack_location_code": "string max 20 chars - optional"
}
```

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "id": "uuid",
    "is_loose_selling_enabled": false,
    "is_online_visible": true,
    "reorder_level": 60,
    "rack_locations": ["A1-03"],
    "updated_at": "2026-07-24T07:00:00Z"
  }
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `INVALID_REORDER_LEVEL` | `reorder_level` is negative |
| 403 | `PLAN_FEATURE_LOCKED` | `is_online_visible = true` attempted on Free/Starter plan |
| 404 | `PRODUCT_NOT_FOUND` | Product ID does not exist |

---

### 5. Edit Product Master Info

```
PATCH /api/v1/pharmacy/inventory/:product_id/details
```

**Authentication:** Bearer JWT - `pharmacy_owner`
**Rate Limit:** 30 req/min

**Request Body (application/json):**

```json
{
  "name": "string max 200 - optional",
  "salt_composition": "string max 500 - optional",
  "manufacturer": "string max 200 - optional",
  "pack_size": "integer > 0 - optional",
  "pack_unit": "string e.g. tablets, ml, g - optional",
  "category_id": "UUID - optional",
  "form": "TABLET | SYRUP | CAPSULE | DROPS | INJECTION | POWDER | CREAM | GEL | OTHER - optional",
  "schedule": "OTC | H | H1 | X | G | OTHER - optional",
  "hsn_code": "string 8 digits - optional",
  "gst_pct": "number - 0 | 5 | 12 | 18 | 28 - optional",
  "rack_locations": ["string array of rack codes - optional"],
  "product_photo_url": "string URL - optional"
}
```

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "id": "uuid",
    "name": "Paracetamol 500mg Tab",
    "hsn_code": "30049099",
    "gst_pct": 12,
    "updated_at": "2026-07-24T07:05:00Z"
  }
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `INVALID_GST_PCT` | GST percentage not in allowed slabs |
| 400 | `INVALID_HSN_CODE` | HSN code not exactly 8 digits |
| 404 | `PRODUCT_NOT_FOUND` | Product ID not found for pharmacy |

---

## Data Models

### PharmacyProduct

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID | PK, auto-generated | Unique product record per pharmacy |
| `pharmacy_id` | UUID | FK ? Pharmacy, NOT NULL | Owning pharmacy |
| `master_medicine_id` | UUID | FK ? MasterMedicine, nullable | Global catalog link |
| `name` | VARCHAR(200) | NOT NULL | Display name |
| `salt_composition` | VARCHAR(500) | nullable | Active ingredient(s) |
| `manufacturer` | VARCHAR(200) | nullable | Brand/manufacturer name |
| `pack_size` | INTEGER | > 0, NOT NULL | Units per pack (e.g., 15 for 15-tablet strip) |
| `pack_unit` | VARCHAR(50) | NOT NULL | Unit label: tablets, ml, g, etc. |
| `category_id` | UUID | FK ? ProductCategory, nullable | Category classification |
| `form` | ENUM | NOT NULL | TABLET / SYRUP / CAPSULE / DROPS / INJECTION / POWDER / CREAM / GEL / OTHER |
| `schedule` | ENUM | NOT NULL, default OTC | OTC / H / H1 / X / G / OTHER |
| `hsn_code` | VARCHAR(8) | nullable | GST HSN code |
| `gst_pct` | NUMERIC(5,2) | NOT NULL, default 12 | GST rate: 0 / 5 / 12 / 18 / 28 |
| `mrp` | NUMERIC(10,2) | NOT NULL | Maximum retail price per pack |
| `is_rx_only` | BOOLEAN | NOT NULL, default false | Requires prescription |
| `is_loose_selling_enabled` | BOOLEAN | NOT NULL, default false | Allow per-unit sale |
| `is_online_visible` | BOOLEAN | NOT NULL, default false | Visible on customer app |
| `reorder_level` | INTEGER | ? 0, default 0 | Stock units at which alert triggers |
| `rack_locations` | TEXT[] | nullable | Array of rack location codes |
| `total_stock_units` | INTEGER | computed | Sum of active batch quantities |
| `total_batches` | INTEGER | computed | Count of active batches |
| `earliest_expiry` | DATE | computed | Min expiry date across active batches |
| `product_photo_url` | TEXT | nullable | S3/CDN URL for product image |
| `created_at` | TIMESTAMPTZ | NOT NULL, default now() | Creation timestamp |
| `updated_at` | TIMESTAMPTZ | NOT NULL, auto-update | Last update timestamp |

---

## Acceptance Criteria

- [ ] Given a pharmacy_staff JWT, when `GET /api/v1/pharmacy/inventory?tab=LOW_STOCK` is called, then only products where `total_stock_units ? reorder_level` are returned.
- [ ] Given a product with `earliest_expiry` within 4 months, when the inventory list is fetched with `tab=EXPIRING`, then that product appears in the result.
- [ ] Given a product with no sale or purchase movement in 90+ days, when `tab=ALERTS` is queried, then the product is tagged `dead_stock` in the flags array.
- [ ] Given a `pharmacy_staff` role, when `PATCH /api/v1/pharmacy/inventory/:id` is called attempting to set `is_online_visible = true`, then a 403 `FORBIDDEN` response is returned.
- [ ] Given a Free-plan pharmacy, when `PATCH /api/v1/pharmacy/inventory/:id` sets `is_online_visible = true`, then a 403 `PLAN_FEATURE_LOCKED` error is returned.
- [ ] Given `export=EXCEL` query param, when `GET /api/v1/pharmacy/inventory` is called, then a valid `.xlsx` file download is returned with all current filter results.
- [ ] Given a search query `q=para`, when the inventory list API is called, then results matching product name, salt composition, and brand containing "para" are returned.
- [ ] Given `PATCH /api/v1/pharmacy/inventory/:id/details` with `gst_pct=7`, then a 400 `INVALID_GST_PCT` error is returned (7 is not an allowed GST slab).

---

## Dependencies

- **EPIC-006 / STORY-004 (Purchase/GRN):** Products are created here; this story only updates them.
- **EPIC-001 (Master Medicine Catalog):** `master_medicine_id` optional link for catalog-linked products.
- **EPIC-007 / STORY-001 (POS):** `is_rx_only`, `is_loose_selling_enabled`, and batch data are consumed at checkout.
- **EPIC-002 (Online Store):** `is_online_visible` gates customer-app visibility.

---

## Notes

- The computed fields (`total_stock_units`, `total_batches`, `earliest_expiry`) should be maintained via database triggers or a materialized view that refreshes on every `ProductBatch` mutation.
- Export files (Excel/PDF) are generated asynchronously for inventories > 500 SKUs; return a `job_id` and poll or webhook when ready.
- `tab_counts` in the meta block are always returned regardless of active tab so the UI can render all filter badges without extra API calls.
