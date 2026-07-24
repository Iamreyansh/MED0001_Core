# STORY-005-005: Pharmacy Catalogue Mapping

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-005-005 |
| **Epic** | EPIC-005 - Master Catalogue |
| **Priority** | P0 |
| **Complexity** | M |
| **Status** | Draft |

---

## Overview

This story covers the mapping layer between master medicine SKUs and a pharmacy's inventory - the mechanism that controls what appears on a pharmacy's online store. A pharmacy creates a mapping by selecting a master medicine, setting their selling price (must be ? MRP and ? any active price ceiling), and providing initial stock quantity. The mapping can be independently enabled or disabled to show/hide the medicine on the online store without removing it from the pharmacy's inventory. Admin can view which pharmacies stock any given medicine and bulk-map medicines to multiple pharmacies for generic roll-outs. Stock quantity auto-decrements on each order line item fulfilment.

---

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| `pharmacy_owner` | Full CRUD on own | Create, update, delete own pharmacy mappings |
| `pharmacy_staff` | Read + Update (own) | View own mappings and update stock quantities |
| `admin_super` | Full + bulk map | View all mappings; perform bulk mapping |
| `admin_operations` | Read + bulk map | View all mappings; perform bulk mapping |
| `admin_compliance` | Read | View all mappings for compliance inspection |
| `customer` | Implicit (read via search) | Sees mapped medicines via search and pharmacy storefront |

---

## Business Rules

1. **Pharmacy price must be ? master MRP**: The `pharmacy_price` set during mapping (or update) must not exceed the medicine's `mrp` in the master catalogue. Attempting to set a higher price returns `PRICE_ABOVE_MRP`. If an active price ceiling exists, `pharmacy_price` must also be ? `mrp_ceiling`; violation returns `PRICE_ABOVE_CEILING`.
2. **Mapping is what makes a medicine appear on the pharmacy's online store**: A medicine in the pharmacy's physical inventory (custom POS SKU) is NOT visible on the online store unless a `PharmacyCatalogueMapping` record exists linking it to a master medicine ID. The mapping's `is_visible` flag controls online storefront visibility independently.
3. **`is_visible` toggles online storefront independently**: Setting `is_visible=false` on a mapping hides the medicine from the pharmacy's online store but does NOT remove it from the POS inventory. The mapping record remains; the medicine just doesn't appear in customer-facing search or the pharmacy's storefront listing. This is the "hide from store" feature.
4. **Only one active mapping per master medicine per pharmacy**: A pharmacy cannot have two active mappings for the same `master_medicine_id`. Attempting to create a duplicate returns `MAPPING_ALREADY_EXISTS`. To update, use the PATCH endpoint on the existing mapping.
5. **Stock quantity auto-updates on sales**: When an order containing a mapped medicine is fulfilled, the order service (EPIC-008) decrements `stock_quantity` by the ordered quantity. When `stock_quantity` reaches 0, the medicine is automatically hidden from the online store (`is_visible` remains true but the search endpoint filters out `stock_quantity=0` items unless `show_oos=true` is passed).
6. **Deleting a mapping removes from online store but keeps POS record**: DELETE `/pharmacy/catalogue-mapping/:mapping_id` removes the mapping and hides the medicine from the online storefront. It does not affect the pharmacy's physical inventory or any custom POS SKU records.
7. **Schedule X medicines cannot be mapped to online store**: Attempting to create a mapping for a medicine with `schedule=X` returns `SCHEDULE_X_NOT_AVAILABLE_ONLINE`. Schedule X medicines can only be dispensed in-store.
8. **Admin bulk map sets default pricing from MRP**: The admin bulk-map endpoint (`POST /admin/catalogue/bulk-map`) with `auto_price_from_mrp=true` sets `pharmacy_price` equal to the master `mrp` for all pharmacies. Pharmacies can subsequently lower their price via the PATCH endpoint.
9. **Banned medicines cannot be mapped**: If a master medicine has `is_banned=true`, attempting to create a mapping returns `MEDICINE_IS_BANNED`. If a medicine is banned after mapping, all mappings are hidden (set `is_visible=false`) by the ban background job (STORY-005-001).
10. **Mapping requires an ACTIVE pharmacy**: Only pharmacies with `status=ACTIVE` can create catalogue mappings. Attempting to map for a PENDING_KYC or SUSPENDED pharmacy returns `PHARMACY_NOT_ACTIVE`.

---

## API Endpoints

### 1. Get Pharmacy Catalogue Mappings

```
GET /api/v1/pharmacy/catalogue-mapping
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 60 req/min

**Query Parameters:**
| Param | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `is_visible` | boolean | No | - | Filter by storefront visibility |
| `in_stock` | boolean | No | - | true = stock_quantity > 0 only |
| `category_id` | UUID | No | - | Filter by category |
| `search` | string | No | - | Search within own mapped medicines (name, salt) |
| `sort` | string | No | `name` | name \| pharmacy_price \| stock_quantity \| created_at |
| `order` | string | No | `asc` | asc \| desc |
| `page` | integer | No | 1 | Page number |
| `limit` | integer | No | 20 | Records per page, max 100 |

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "pharmacy_id": "uuid-v4",
    "mappings": [
      {
        "mapping_id": "uuid-v4",
        "master_medicine_id": "uuid-v4",
        "name": "Augmentin 625 Tablet",
        "salt_composition": "Amoxicillin (500mg) + Clavulanic Acid (125mg)",
        "manufacturer": "GSK India",
        "category": { "name": "Antibiotics" },
        "form": "TABLET",
        "pack_size": 10,
        "schedule": "H",
        "is_rx_only": true,
        "master_mrp": 218.50,
        "mrp_ceiling": null,
        "pharmacy_price": 215.00,
        "stock_quantity": 48,
        "is_visible": true,
        "created_at": "2026-06-15T00:00:00Z",
        "updated_at": "2026-07-20T00:00:00Z"
      }
    ]
  },
  "meta": {
    "page": 1,
    "limit": 20,
    "total": 234,
    "total_pages": 12
  }
}
```

---

### 2. Create Catalogue Mapping

```
POST /api/v1/pharmacy/catalogue-mapping
```

**Authentication:** Bearer JWT - `pharmacy_owner`
**Rate Limit:** 60 req/min

**Request Body (application/json):**
```json
{
  "master_medicine_id": "string (UUID) - required, must be a valid, non-banned master medicine",
  "pharmacy_price": "number - required, positive decimal, must be ? master MRP and ? mrp_ceiling if set",
  "stock_quantity": "integer - required, non-negative, initial stock count"
}
```

**Success Response - 201 Created:**
```json
{
  "success": true,
  "data": {
    "mapping_id": "uuid-v4",
    "pharmacy_id": "uuid-v4",
    "master_medicine_id": "uuid-v4",
    "medicine_name": "Augmentin 625 Tablet",
    "pharmacy_price": 215.00,
    "master_mrp": 218.50,
    "mrp_ceiling": null,
    "stock_quantity": 48,
    "is_visible": true,
    "created_at": "2026-07-24T00:00:00Z"
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `PRICE_ABOVE_MRP` | `pharmacy_price` > master medicine `mrp` |
| 400 | `PRICE_ABOVE_CEILING` | `pharmacy_price` > active `mrp_ceiling` |
| 400 | `NEGATIVE_STOCK` | `stock_quantity` < 0 |
| 403 | `PHARMACY_NOT_ACTIVE` | Pharmacy is not in ACTIVE status |
| 404 | `MEDICINE_NOT_FOUND` | `master_medicine_id` not found |
| 409 | `MAPPING_ALREADY_EXISTS` | Pharmacy already has a mapping for this master medicine |
| 409 | `MEDICINE_IS_BANNED` | Master medicine is banned |
| 409 | `SCHEDULE_X_NOT_AVAILABLE_ONLINE` | Medicine is Schedule X |

---

### 3. Update Catalogue Mapping (Price / Stock / Visibility)

```
PATCH /api/v1/pharmacy/catalogue-mapping/:mapping_id
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 120 req/min

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| `mapping_id` | UUID | Mapping record ID |

**Request Body (application/json):**
```json
{
  "pharmacy_price": "number - optional, must be ? master MRP and ? mrp_ceiling",
  "stock_quantity": "integer - optional, non-negative; manual stock adjustment",
  "is_visible": "boolean - optional; toggle online store visibility"
}
```

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "mapping_id": "uuid-v4",
    "updated_fields": ["pharmacy_price", "is_visible"],
    "pharmacy_price": 210.00,
    "stock_quantity": 48,
    "is_visible": false,
    "updated_at": "2026-07-24T00:00:00Z"
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `PRICE_ABOVE_MRP` | New price exceeds master MRP |
| 400 | `PRICE_ABOVE_CEILING` | New price exceeds active ceiling |
| 403 | `FORBIDDEN` | Caller does not own this pharmacy's mapping |
| 404 | `MAPPING_NOT_FOUND` | mapping_id not found for this pharmacy |

---

### 4. Delete Catalogue Mapping

```
DELETE /api/v1/pharmacy/catalogue-mapping/:mapping_id
```

**Authentication:** Bearer JWT - `pharmacy_owner`
**Rate Limit:** 30 req/min

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| `mapping_id` | UUID | Mapping record ID |

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "mapping_id": "uuid-v4",
    "deleted": true,
    "medicine_name": "Augmentin 625 Tablet",
    "message": "Medicine removed from your online store. Physical inventory is not affected."
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 403 | `FORBIDDEN` | Caller does not own this mapping |
| 404 | `MAPPING_NOT_FOUND` | mapping_id not found |

---

### 5. Admin - View Which Pharmacies Stock a Medicine

```
GET /api/v1/admin/catalogue/:master_id/pharmacy-mappings
```

**Authentication:** Bearer JWT - `admin_super`, `admin_operations`, `admin_compliance`
**Rate Limit:** 60 req/min

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| `master_id` | UUID | Master medicine ID |

**Query Parameters:**
| Param | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `zone_id` | UUID | No | - | Filter by zone |
| `is_visible` | boolean | No | - | Filter by storefront visibility |
| `above_ceiling` | boolean | No | false | true = only pharmacies whose price exceeds ceiling |
| `page` | integer | No | 1 | Page |
| `limit` | integer | No | 20 | Per page, max 100 |

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "master_medicine_id": "uuid-v4",
    "medicine_name": "Augmentin 625 Tablet",
    "master_mrp": 218.50,
    "mrp_ceiling": null,
    "total_pharmacies_stocking": 187,
    "pharmacies": [
      {
        "mapping_id": "uuid-v4",
        "pharmacy_id": "uuid-v4",
        "pharmacy_name": "Sharma Medical Store",
        "zone": "Koramangala Zone",
        "pharmacy_price": 215.00,
        "stock_quantity": 48,
        "is_visible": true,
        "is_above_ceiling": false,
        "created_at": "2026-06-15T00:00:00Z"
      }
    ]
  },
  "meta": {
    "page": 1,
    "limit": 20,
    "total": 187,
    "total_pages": 10
  }
}
```

---

### 6. Admin - Bulk Map Medicine to Multiple Pharmacies

```
POST /api/v1/admin/catalogue/bulk-map
```

**Authentication:** Bearer JWT - `admin_super`, `admin_operations`
**Rate Limit:** 5 req/min per admin

**Request Body (application/json):**
```json
{
  "master_medicine_id": "string (UUID) - required, master medicine to map",
  "pharmacy_ids": ["uuid-v4", "uuid-v4"],
  "auto_price_from_mrp": "boolean - required; if true, pharmacy_price = master MRP for all; if false, pharmacy_price must be provided per pharmacy",
  "pharmacy_price": "number - required only when auto_price_from_mrp=false; applied to all pharmacies",
  "initial_stock_quantity": "integer - optional, default 0; initial stock for each pharmacy"
}
```

**Success Response - 202 Accepted:**
```json
{
  "success": true,
  "data": {
    "job_id": "uuid-v4",
    "master_medicine_id": "uuid-v4",
    "medicine_name": "Augmentin 625 Tablet",
    "total_pharmacies": 45,
    "status": "QUEUED",
    "estimated_completion_seconds": 15,
    "poll_url": "/api/v1/admin/bulk-jobs/uuid-v4"
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `PHARMACY_IDS_REQUIRED` | `pharmacy_ids` is empty |
| 400 | `TOO_MANY_PHARMACIES` | More than 200 pharmacies in a single bulk map |
| 400 | `PRICE_ABOVE_MRP` | Provided `pharmacy_price` exceeds master MRP |
| 403 | `FORBIDDEN` | Caller not admin_super or admin_operations |
| 404 | `MEDICINE_NOT_FOUND` | master_medicine_id not found |
| 409 | `MEDICINE_IS_BANNED` | Cannot bulk-map a banned medicine |

---

## Data Models

### PharmacyCatalogueMapping

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID v4 | PK | Unique mapping identifier |
| `pharmacy_id` | UUID | FK ? Pharmacy.id, not null | Owning pharmacy |
| `master_medicine_id` | UUID | FK ? MedicineMaster.id, not null | Master medicine being mapped |
| `pharmacy_price` | DECIMAL(10,2) | Not null, positive | Pharmacy's selling price (online store) |
| `stock_quantity` | INTEGER | Not null, default 0, ? 0 | Current stock count |
| `is_visible` | BOOLEAN | Not null, default true | Whether shown on online storefront |
| `created_at` | TIMESTAMPTZ | Not null, default now() | Mapping creation timestamp |
| `updated_at` | TIMESTAMPTZ | Not null | Last update timestamp |
| UNIQUE | - | (pharmacy_id, master_medicine_id) | One mapping per medicine per pharmacy |

---

## Acceptance Criteria

- [ ] **Given** POST `/api/v1/pharmacy/catalogue-mapping` with `pharmacy_price=220` for a medicine with `mrp=218.50`, **then** HTTP 400 `PRICE_ABOVE_MRP` is returned and no mapping is created.
- [ ] **Given** a valid mapping is created with `stock_quantity=48`, **then** the medicine appears in the pharmacy's online storefront and in customer search results for that zone.
- [ ] **Given** PATCH `/api/v1/pharmacy/catalogue-mapping/:mapping_id` with `is_visible=false`, **then** the medicine is hidden from the pharmacy's online storefront and excluded from customer search results, but the mapping record and stock quantity remain intact.
- [ ] **Given** the same `master_medicine_id` is mapped twice for the same pharmacy, **then** the second POST returns HTTP 409 `MAPPING_ALREADY_EXISTS`.
- [ ] **Given** POST `/api/v1/pharmacy/catalogue-mapping` for a medicine with `schedule=X`, **then** HTTP 409 `SCHEDULE_X_NOT_AVAILABLE_ONLINE` is returned.
- [ ] **Given** GET `/api/v1/admin/catalogue/:master_id/pharmacy-mappings?above_ceiling=true`, **then** only pharmacies whose `pharmacy_price` exceeds the active `mrp_ceiling` for this medicine are returned.
- [ ] **Given** POST `/api/v1/admin/catalogue/bulk-map` with `auto_price_from_mrp=true` for 45 pharmacies, **then** HTTP 202 is returned with a `job_id`, and when the job completes, all 45 pharmacies have a `PharmacyCatalogueMapping` with `pharmacy_price = master mrp` and `initial_stock_quantity`.
- [ ] **Given** a master medicine is banned (STORY-005-001), **then** all `PharmacyCatalogueMapping.is_visible` for that medicine are set to `false` within 30 seconds, and the medicine disappears from all pharmacy storefronts and customer search results.

---

## Dependencies

- STORY-005-001 - Medicine Master CRUD (master medicine must exist and not be banned)
- STORY-005-004 - Price Ceiling Management (ceiling enforced on price during create/update)
- STORY-005-003 - Search & Discovery (mapping enables medicine to appear in search)
- EPIC-006 - Pharmacy Inventory (stock_quantity is decremented by order fulfilment)
- EPIC-008 - Orders (availability check uses mapping stock; price ceiling validated)
- Infrastructure: PostgreSQL unique index on (pharmacy_id, master_medicine_id)

---

## Notes

- The `stock_quantity` field on `PharmacyCatalogueMapping` is the source of truth for online store stock. The pharmacy's physical inventory batch/expiry records (EPIC-006) are a separate system. Reconciliation between the two is the pharmacy's responsibility (via manual stock adjustment via PATCH or automated sync if pharmacy uses the platform's inventory module).
- Stock decrement on order fulfilment: EPIC-008 calls an internal service method `CatalogueService.decrementStock(mapping_id, quantity)` wrapped in the order transaction. If stock would go below 0, the decrement is clamped to 0.
- When `stock_quantity` reaches 0, the medicine is excluded from customer search by default (`WHERE stock_quantity > 0`). Customers can still navigate directly to the medicine detail page and see "Out of stock at this pharmacy."
- Bulk map job result: pharmacies where a mapping already exists (`MAPPING_ALREADY_EXISTS`) are counted as `skipped`, not failed. The bulk job report lists skipped pharmacies with the reason.
- Performance: the pharmacy catalogue-mapping list endpoint should use a covering index on `(pharmacy_id, is_visible, stock_quantity, master_medicine_id)` to avoid table scans for large pharmacies with 500+ mappings.
