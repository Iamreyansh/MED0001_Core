# STORY-003: Rack Location Management - Physical Shelf Mapping

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-003 |
| **Epic** | EPIC-006 - Pharmacy Inventory |
| **Priority** | P1 |
| **Complexity** | M |
| **Status** | Draft |

---

## Overview

This story enables pharmacies to define a physical rack/bin hierarchy within their store and map medicines to their shelf locations. When a pharmacist creates a sale at the POS, they can search by rack code to immediately find the medicine's physical location, reducing dispensing time and errors. Rack locations are also used in printed dispensing labels. The story includes a bulk-assign feature, an unlocated-products view for gap analysis, and a PDF label-printing endpoint for generating adhesive rack labels.

---

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| `pharmacy_owner` | Full read + write | Create, delete, assign rack locations |
| `pharmacy_staff` | Read + assign | Can view racks and assign medicines; cannot create or delete racks |
| `admin_super` | No access | Internal/operational only |
| `customer` | No access | Not applicable |

---

## Business Rules

1. **Rack code format.** A rack code must follow `[Zone][Rack]-[Bin]` pattern where Zone is uppercase letters (A-Z), Rack is a 1-2 digit number, and Bin is a zero-padded 2-digit number (e.g., `A1-01`, `B12-05`). The system validates this regex on creation.
2. **Rack code uniqueness per pharmacy.** `rack_code` must be unique within a pharmacy. Attempting to create a duplicate rack code returns `RACK_CODE_EXISTS`.
3. **Non-empty rack cannot be deleted.** A rack can only be deleted if `medicine_count = 0`. If any products are still assigned to the rack, the API returns `RACK_NOT_EMPTY` and lists the products blocking deletion.
4. **One product - multiple racks allowed.** A `PharmacyProduct.rack_locations` is an array; the same medicine can be stored across multiple rack locations (e.g., overflow stock). Assignment is additive.
5. **Unlocated products.** Products with an empty `rack_locations` array appear in the `UNALLOCATED` inventory tab and the `/unlocated` endpoint. The pharmacy KPI card tracks this count.
6. **POS rack-code search.** The POS search endpoint (EPIC-007 / STORY-001) accepts a `rack_code` as input and returns all products stored at that location. This enables staff to navigate to a shelf and confirm available medicines.
7. **Label PDF format.** The `/print-labels` endpoint generates an A4 PDF with 24 labels per page (3 columns - 8 rows), each containing: rack code in large text, zone name, a QR code linking to the rack detail URL, and a medicine count badge.
8. **Bulk assign is idempotent.** If a product is already assigned to the specified rack, repeating the assignment has no effect (no duplicate entries in the array).

---

## API Endpoints

### 1. List All Rack Locations

```
GET /api/v1/pharmacy/rack-locations
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 60 req/min

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `zone` | string | - | Filter by zone name |
| `q` | string | - | Search by rack code |

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "kpi": {
      "racks_count": 42,
      "zones_count": 4,
      "medicines_mapped_count": 319,
      "unlocated_count": 23
    },
    "racks": [
      {
        "rack_code": "A1-01",
        "zone_name": "Zone A",
        "description": "Antibiotics shelf 1",
        "medicine_count": 12,
        "medicines_preview": [
          { "product_id": "uuid", "name": "Amoxicillin 250mg Cap" },
          { "product_id": "uuid", "name": "Azithromycin 500mg Tab" }
        ],
        "created_at": "2026-01-10T08:00:00Z"
      }
    ]
  },
  "meta": {
    "page": 1,
    "limit": 50,
    "total": 42
  }
}
```

---

### 2. Create a Rack

```
POST /api/v1/pharmacy/rack-locations
```

**Authentication:** Bearer JWT - `pharmacy_owner`
**Rate Limit:** 30 req/min

**Request Body (application/json):**

```json
{
  "rack_code": "string - required, format [A-Z]{1,2}[0-9]{1,2}-[0-9]{2}",
  "zone_name": "string max 100 - required",
  "description": "string max 300 - optional"
}
```

**Success Response - 201 Created:**

```json
{
  "success": true,
  "data": {
    "rack_code": "C3-07",
    "zone_name": "Zone C",
    "description": "OTC vitamins section",
    "medicine_count": 0,
    "created_at": "2026-07-24T09:00:00Z"
  }
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `INVALID_RACK_CODE_FORMAT` | rack_code does not match required pattern |
| 409 | `RACK_CODE_EXISTS` | rack_code already exists for this pharmacy |

---

### 3. Delete a Rack

```
DELETE /api/v1/pharmacy/rack-locations/:rack_code
```

**Authentication:** Bearer JWT - `pharmacy_owner`
**Rate Limit:** 10 req/min

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "rack_code": "C3-07",
    "deleted_at": "2026-07-24T09:05:00Z"
  }
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `RACK_NOT_EMPTY` | Rack still has medicines assigned |
| 404 | `RACK_NOT_FOUND` | `rack_code` not found for this pharmacy |

---

### 4. Rack Detail (Medicines in Rack)

```
GET /api/v1/pharmacy/rack-locations/:rack_code
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 60 req/min

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "rack_code": "A1-01",
    "zone_name": "Zone A",
    "description": "Antibiotics shelf 1",
    "medicine_count": 12,
    "medicines": [
      {
        "product_id": "uuid",
        "name": "Amoxicillin 250mg Cap",
        "form": "CAPSULE",
        "pack_size": 10,
        "total_stock_units": 200,
        "mrp": 45.00,
        "is_rx_only": true,
        "earliest_expiry": "2027-02-28"
      }
    ]
  }
}
```

---

### 5. Bulk Assign Rack to Products

```
POST /api/v1/pharmacy/rack-locations/assign
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 20 req/min

**Request Body (application/json):**

```json
{
  "product_ids": ["uuid", "uuid", "uuid"],
  "rack_code": "A1-01"
}
```

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "rack_code": "A1-01",
    "assigned_count": 3,
    "skipped_count": 0,
    "product_ids_assigned": ["uuid", "uuid", "uuid"]
  }
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 404 | `RACK_NOT_FOUND` | `rack_code` not found |
| 400 | `EMPTY_PRODUCT_LIST` | `product_ids` array is empty |

---

### 6. Update Product Rack Location

```
PATCH /api/v1/pharmacy/inventory/:product_id/rack
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 60 req/min

**Request Body (application/json):**

```json
{
  "rack_code": "string - required",
  "action": "ADD | REMOVE - default ADD"
}
```

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "product_id": "uuid",
    "rack_locations": ["A1-01", "B2-03"],
    "updated_at": "2026-07-24T09:10:00Z"
  }
}
```

---

### 7. Unlocated Products

```
GET /api/v1/pharmacy/rack-locations/unlocated
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 30 req/min

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "unlocated_count": 23,
    "products": [
      {
        "product_id": "uuid",
        "name": "Vitamin D3 60K IU Cap",
        "form": "CAPSULE",
        "total_stock_units": 120,
        "category_name": "Vitamins & Supplements"
      }
    ]
  },
  "meta": {
    "page": 1,
    "limit": 20,
    "total": 23
  }
}
```

---

### 8. Print Rack Labels (PDF)

```
POST /api/v1/pharmacy/rack-locations/print-labels
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 5 req/min

**Request Body (application/json):**

```json
{
  "rack_codes": ["A1-01", "A1-02", "B2-03"]
}
```

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "pdf_url": "https://cdn.medmate.in/pharmacy/uuid/rack-labels-20260724.pdf",
    "expires_at": "2026-07-24T11:00:00Z",
    "label_count": 3
  }
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `EMPTY_RACK_CODES` | `rack_codes` array is empty |
| 400 | `TOO_MANY_LABELS` | More than 120 rack codes in one request |
| 404 | `RACK_CODES_NOT_FOUND` | One or more rack codes invalid; returns invalid list |

---

## Data Models

### RackLocation

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID | PK, auto-generated | Unique rack record |
| `pharmacy_id` | UUID | FK ? Pharmacy, NOT NULL | Owning pharmacy |
| `rack_code` | VARCHAR(20) | NOT NULL, UNIQUE per pharmacy | Physical identifier (e.g., A1-01) |
| `zone_name` | VARCHAR(100) | NOT NULL | Zone label (e.g., Zone A) |
| `description` | VARCHAR(300) | nullable | Human-readable shelf description |
| `created_at` | TIMESTAMPTZ | NOT NULL, default now() | Creation timestamp |
| `updated_at` | TIMESTAMPTZ | NOT NULL, auto-update | Last update timestamp |

> Note: Product-to-rack mapping is stored in `PharmacyProduct.rack_locations (TEXT[])`. A separate join table is not required given the array storage.

---

## Acceptance Criteria

- [ ] Given `POST /rack-locations` with `rack_code = "Z99-99"`, then the rack is created and returns 201.
- [ ] Given `POST /rack-locations` with `rack_code = "invalid_code"`, then a 400 `INVALID_RACK_CODE_FORMAT` error is returned.
- [ ] Given `DELETE /rack-locations/A1-01` when 12 products are assigned to A1-01, then a 400 `RACK_NOT_EMPTY` error is returned with a list of blocking products.
- [ ] Given `POST /rack-locations/assign` with 3 valid `product_ids` and a valid `rack_code`, then all 3 products have `rack_code` appended to their `rack_locations` array.
- [ ] Given a product already assigned to `A1-01`, when `POST /rack-locations/assign` is called again with `A1-01`, then the assignment is idempotent and `rack_locations` remains unchanged.
- [ ] Given `POST /rack-locations/print-labels` with `rack_codes: ["A1-01", "B2-03"]`, then a signed PDF URL is returned referencing a valid 2-label document.
- [ ] Given `GET /rack-locations/unlocated`, then only products with an empty `rack_locations` array are returned.
- [ ] Given a POS search with `mode=TEXT` and query `"A1-01"`, then products at that rack location are returned in the search results (tested via EPIC-007 / STORY-001).

---

## Dependencies

- **EPIC-006 / STORY-001 (Inventory Management):** `PharmacyProduct.rack_locations` array is the primary storage.
- **EPIC-007 / STORY-001 (POS):** POS product search supports rack_code lookup.
- **Notification Service / PDF Generator:** Label PDF generation requires server-side PDF rendering (WeasyPrint or headless Chrome).

---

## Notes

- The `rack_locations` field on `PharmacyProduct` is a `TEXT[]` (Postgres array). An alternative normalized design (junction table) should be considered if advanced rack analytics or complex queries are required in a future phase.
- Zone names are free-text; there is no separate Zones entity. If zone management (rename/delete) is needed in future, a `PharmacyZone` table should be introduced.
- The QR code in the PDF label encodes `https://app.medmate.in/pharmacy/{pharmacy_id}/rack/{rack_code}` - a deep link to the rack detail screen.
