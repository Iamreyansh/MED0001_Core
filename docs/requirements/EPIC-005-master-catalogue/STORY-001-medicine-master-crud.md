# STORY-005-001: Medicine Master CRUD

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-005-001 |
| **Epic** | EPIC-005 - Master Catalogue |
| **Priority** | P0 |
| **Complexity** | M |
| **Status** | Draft |

---

## Overview

This story covers the admin-managed master medicine database - the canonical source of truth for all medicines available on the Namma MedMate platform. Admin catalogue managers create, update, view, and ban/un-ban medicine records. Each record includes regulatory metadata (schedule classification, HSN code, GST rate), composition data (salt, form, pack size), and market data (MRP, monthly demand, pharmacy mapping count). Banning a medicine triggers immediate removal from all pharmacy storefronts. All changes are audit-logged. The master catalogue is the foundation from which pharmacies map their inventory and customers discover products.

---

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| `admin_super` | Full CRUD + ban/unban | Full catalogue management |
| `admin_operations` | Read, Create, Update | Can add and update medicines; cannot ban |
| `admin_compliance` | Read, Update (schedule) | Can update schedule classification and ban/unban for compliance |
| `pharmacy_owner` | Read (via mapping endpoints) | Can view master medicines to map to own inventory |
| `pharmacy_staff` | Read (via mapping endpoints) | Same as pharmacy_owner for read |
| Public / `customer` | Read (search + detail) | Can search and view medicine details (no auth) |

---

## Business Rules

1. **Medicine uniqueness constraint**: A master medicine record is unique per combination of `salt_composition + manufacturer + form + pack_size + pack_unit`. Attempting to create a duplicate combination returns `DUPLICATE_MEDICINE`. Names can differ (brand names vs generics) but the combination must be unique.
2. **HSN code must be a valid 8-digit pharma HSN**: HSN codes for medicines fall in chapters 30 (pharmaceutical products), 29 (organic chemicals for APIs), or 90 (medical devices). The API validates that the HSN code is exactly 8 numeric digits and exists in the HSN reference table. Invalid codes return `INVALID_HSN_CODE`.
3. **GST rates for medicines are restricted to 5%, 12%, or 18%**: Most OTC and prescription medicines are taxed at 5%. Medical devices and some nutraceuticals are 12% or 18%. Any other value returns `INVALID_GST_RATE`.
4. **Banning immediately removes medicine from all pharmacy storefronts**: POST `/ban` on a medicine sets `is_banned=true` and triggers a background job that sets `is_visible=false` on all `PharmacyCatalogueMapping` records for this medicine. The job must complete within 30 seconds. The medicine is excluded from all search and discovery results immediately.
5. **Schedule H medicines require prescription**: In the customer app and order flow, any medicine with `schedule=H`, `H1`, or `X` sets `is_rx_only=true` regardless of the `is_rx_only` field value. The `is_rx_only` field is enforced by business logic, not just by the database value.
6. **Schedule H1 requires special register maintenance**: Medicines with `schedule=H1` (sulphonamides, antihistamines, antibiotics) require pharmacy staff to maintain a separate dispensing register. The admin UI surfaces a warning on H1 medicines.
7. **Schedule X medicines have strictest controls**: Schedule X (narcotic/psychotropic) medicines require a separate prescription in triplicate, patient ID verification, and are not available for delivery via the customer app (only in-store dispensing). Attempting to add a Schedule X medicine to the online storefront returns `SCHEDULE_X_NOT_AVAILABLE_ONLINE`.
8. **Monthly demand is a read-only computed field**: `monthly_demand` is updated by a nightly aggregation job that counts orders containing this medicine in the trailing 30 days across all pharmacies. It cannot be set via the API.
9. **Substitute medicines must be existing master records**: The `substitutes` array in POST/PATCH must contain valid `MedicineMaster.id` values. Substitutes are typically medicines with the same `salt_composition` but different manufacturer, form, or brand. Circular substitution (A?B?A) is allowed.
10. **All catalogue changes are audit-logged**: Every create, update, ban, and unban event is recorded in `AuditLog` with actor_id, action, entity_id, and changed fields with before/after values.

---

## API Endpoints

### 1. List Master Medicines (Admin)

```
GET /api/v1/admin/catalogue
```

**Authentication:** Bearer JWT - `admin_super`, `admin_operations`, `admin_compliance`
**Rate Limit:** 60 req/min

**Query Parameters:**
| Param | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `category_id` | UUID | No | - | Filter by category |
| `schedule` | string | No | - | Filter: OTC \| H \| H1 \| X |
| `gst_pct` | integer | No | - | Filter: 5 \| 12 \| 18 |
| `is_rx_only` | boolean | No | - | Filter by prescription requirement |
| `is_banned` | boolean | No | false | true to include banned medicines |
| `search` | string | No | - | Full-text search: name, salt, manufacturer, HSN |
| `sort` | string | No | `name` | name \| monthly_demand \| mapped_pharmacy_count \| mrp \| created_at |
| `order` | string | No | `asc` | asc \| desc |
| `page` | integer | No | 1 | Page number |
| `limit` | integer | No | 20 | Records per page, max 100 |

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "medicines": [
      {
        "medicine_id": "uuid-v4",
        "name": "Augmentin 625 Tablet",
        "salt_composition": "Amoxicillin (500mg) + Clavulanic Acid (125mg)",
        "manufacturer": "GSK India",
        "category": {
          "category_id": "uuid-v4",
          "name": "Antibiotics"
        },
        "form": "TABLET",
        "pack_size": 10,
        "pack_unit": "TABLET",
        "schedule": "H",
        "hsn_code": "30041090",
        "gst_pct": 12,
        "mrp": 218.50,
        "is_rx_only": true,
        "is_banned": false,
        "monthly_demand": 4280,
        "mapped_pharmacy_count": 187,
        "created_at": "2026-01-15T00:00:00Z"
      }
    ]
  },
  "meta": {
    "page": 1,
    "limit": 20,
    "total": 52438,
    "total_pages": 2622
  }
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `INVALID_SCHEDULE` | Schedule filter not in OTC/H/H1/X |
| 400 | `INVALID_GST_RATE` | GST filter not 5, 12, or 18 |
| 403 | `FORBIDDEN` | Caller not an admin role |

---

### 2. Get Catalogue Summary KPIs

```
GET /api/v1/admin/catalogue/summary
```

**Authentication:** Bearer JWT - `admin_super`, `admin_operations`, `admin_compliance`
**Rate Limit:** 60 req/min

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "total_skus": 52438,
    "category_count": 48,
    "rx_only_count": 38214,
    "otc_count": 14224,
    "banned_count": 12,
    "schedule_h_count": 31050,
    "schedule_h1_count": 4820,
    "schedule_x_count": 344,
    "avg_mrp": 185.40,
    "total_pharmacy_mappings": 4820940,
    "data_as_of": "2026-07-24T02:00:00Z"
  },
  "meta": {}
}
```

---

### 3. Create Master Medicine

```
POST /api/v1/admin/catalogue
```

**Authentication:** Bearer JWT - `admin_super`, `admin_operations`, `admin_compliance`
**Rate Limit:** 30 req/min

**Request Body (application/json):**
```json
{
  "name": "string - required, 2-255 chars, brand/generic name",
  "salt_composition": "string - required, max 500 chars, active ingredients with strengths e.g. 'Paracetamol (500mg)'",
  "manufacturer": "string - required, max 200 chars",
  "category_id": "string (UUID) - required, must be an existing active category",
  "form": "string - required, enum: TABLET | CAPSULE | SYRUP | INJECTION | OINTMENT | DROPS | INHALER | PATCH | POWDER | SUPPOSITORY | OTHER",
  "pack_size": "number - required, positive integer or decimal; quantity per pack (e.g. 10 for 10 tablets, 100 for 100ml syrup)",
  "pack_unit": "string - required, enum: TABLET | CAPSULE | ML | MG | G | STRIP | VIAL | AMPOULE | SACHET | TUBE | BOTTLE",
  "schedule": "string - required, enum: OTC | H | H1 | X",
  "hsn_code": "string - required, exactly 8 numeric digits, valid pharma HSN",
  "gst_pct": "integer - required, enum: 5 | 12 | 18",
  "mrp": "number - required, positive decimal, max retail price in INR",
  "is_rx_only": "boolean - required; true for prescription medicines; auto-set to true for H/H1/X schedules",
  "description": "string - optional, max 2000 chars, plain text medicine description",
  "substitutes": ["string (UUID) - optional, array of master medicine IDs of substitute medicines"]
}
```

**Success Response - 201 Created:**
```json
{
  "success": true,
  "data": {
    "medicine_id": "uuid-v4",
    "name": "Augmentin 625 Tablet",
    "salt_composition": "Amoxicillin (500mg) + Clavulanic Acid (125mg)",
    "manufacturer": "GSK India",
    "category_id": "uuid-v4",
    "form": "TABLET",
    "pack_size": 10,
    "pack_unit": "TABLET",
    "schedule": "H",
    "hsn_code": "30041090",
    "gst_pct": 12,
    "mrp": 218.50,
    "is_rx_only": true,
    "is_banned": false,
    "monthly_demand": 0,
    "mapped_pharmacy_count": 0,
    "substitutes": [],
    "created_by": "admin-uuid-v4",
    "created_at": "2026-07-24T00:00:00Z"
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `INVALID_FORM` | `form` not in allowed enum |
| 400 | `INVALID_SCHEDULE` | `schedule` not in OTC/H/H1/X |
| 400 | `INVALID_HSN_CODE` | HSN not 8 digits or not in pharma HSN reference |
| 400 | `INVALID_GST_RATE` | `gst_pct` not 5, 12, or 18 |
| 400 | `INVALID_CATEGORY` | `category_id` not found or inactive |
| 400 | `INVALID_SUBSTITUTE_ID` | One or more substitute IDs not found |
| 409 | `DUPLICATE_MEDICINE` | salt_composition + manufacturer + form + pack_size + pack_unit already exists |

---

### 4. Get Medicine Detail (Admin)

```
GET /api/v1/admin/catalogue/:id
```

**Authentication:** Bearer JWT - `admin_super`, `admin_operations`, `admin_compliance`
**Rate Limit:** 60 req/min

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| `id` | UUID | Medicine master ID |

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "medicine_id": "uuid-v4",
    "name": "Augmentin 625 Tablet",
    "salt_composition": "Amoxicillin (500mg) + Clavulanic Acid (125mg)",
    "manufacturer": "GSK India",
    "category": { "category_id": "uuid-v4", "name": "Antibiotics" },
    "form": "TABLET",
    "pack_size": 10,
    "pack_unit": "TABLET",
    "schedule": "H",
    "hsn_code": "30041090",
    "gst_pct": 12,
    "mrp": 218.50,
    "mrp_ceiling": null,
    "is_rx_only": true,
    "is_banned": false,
    "ban_reason": null,
    "description": "Augmentin 625 Tablet is a combination antibiotic used for treating bacterial infections.",
    "monthly_demand": 4280,
    "mapped_pharmacy_count": 187,
    "substitutes": [
      { "medicine_id": "uuid-v4", "name": "Mox CV 625 Tablet", "manufacturer": "Cipla" }
    ],
    "stocking_pharmacies": [
      {
        "pharmacy_id": "uuid-v4",
        "pharmacy_name": "Sharma Medical Store",
        "pharmacy_price": 215.00,
        "stock_quantity": 48,
        "is_visible": true
      }
    ],
    "demand_stats": {
      "monthly_demand": 4280,
      "monthly_demand_trend": "STABLE",
      "top_zone": "Koramangala Zone"
    },
    "created_by": "admin-uuid-v4",
    "created_at": "2026-01-15T00:00:00Z",
    "updated_at": "2026-07-10T00:00:00Z"
  },
  "meta": {}
}
```

---

### 5. Update Medicine Details

```
PATCH /api/v1/admin/catalogue/:id
```

**Authentication:** Bearer JWT - `admin_super`, `admin_operations`, `admin_compliance`
**Rate Limit:** 30 req/min

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| `id` | UUID | Medicine master ID |

**Request Body (application/json):**
```json
{
  "name": "string - optional, 2-255 chars",
  "description": "string - optional, max 2000 chars",
  "category_id": "string (UUID) - optional",
  "schedule": "string - optional, enum: OTC | H | H1 | X; changing schedule re-evaluates is_rx_only",
  "gst_pct": "integer - optional, enum: 5 | 12 | 18",
  "mrp": "number - optional, positive decimal; changes must not violate existing price ceilings",
  "is_rx_only": "boolean - optional",
  "substitutes": ["string (UUID) - optional, full replacement of substitutes array"]
}
```

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "medicine_id": "uuid-v4",
    "updated_fields": ["gst_pct", "description"],
    "updated_at": "2026-07-24T00:00:00Z"
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `MRP_BELOW_CEILING` | New MRP is below the existing price ceiling |
| 403 | `FORBIDDEN` | Caller not authorised |
| 404 | `MEDICINE_NOT_FOUND` | Medicine ID not found |
| 409 | `MEDICINE_IS_BANNED` | Cannot update a banned medicine; unban first |

---

### 6. Ban Medicine Platform-Wide

```
POST /api/v1/admin/catalogue/:id/ban
```

**Authentication:** Bearer JWT - `admin_super`, `admin_compliance`
**Rate Limit:** 10 req/min

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| `id` | UUID | Medicine master ID |

**Request Body (application/json):**
```json
{
  "reason": "string - required, max 500 chars, regulatory/compliance reason for ban"
}
```

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "medicine_id": "uuid-v4",
    "is_banned": true,
    "ban_reason": "Banned by CDSCO notification dated 2026-07-01",
    "banned_at": "2026-07-24T00:00:00Z",
    "pharmacy_mappings_hidden": 187,
    "storefront_removal_job_id": "uuid-v4"
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `REASON_REQUIRED` | `reason` is empty |
| 403 | `FORBIDDEN` | Caller not admin_super or admin_compliance |
| 404 | `MEDICINE_NOT_FOUND` | Medicine ID not found |
| 409 | `ALREADY_BANNED` | Medicine is already banned |

---

### 7. Un-ban Medicine

```
POST /api/v1/admin/catalogue/:id/unban
```

**Authentication:** Bearer JWT - `admin_super`, `admin_compliance`
**Rate Limit:** 10 req/min

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| `id` | UUID | Medicine master ID |

**Request Body (application/json):**
```json
{
  "reason": "string - required, max 500 chars, reason for lifting the ban"
}
```

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "medicine_id": "uuid-v4",
    "is_banned": false,
    "unbanned_at": "2026-07-24T00:00:00Z",
    "note": "Pharmacy mappings remain hidden. Pharmacies must manually re-enable items on their storefront."
  },
  "meta": {}
}
```

---

## Data Models

### MedicineMaster

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID v4 | PK | Unique medicine identifier |
| `name` | VARCHAR(255) | Not null | Brand or generic name |
| `salt_composition` | TEXT | Not null | Active ingredients with strengths |
| `manufacturer` | VARCHAR(200) | Not null | Manufacturer name |
| `category_id` | UUID | FK ? MedicineCategory.id, not null | Therapeutic category |
| `form` | ENUM | Not null | TABLET \| CAPSULE \| SYRUP \| INJECTION \| OINTMENT \| DROPS \| INHALER \| PATCH \| POWDER \| SUPPOSITORY \| OTHER |
| `pack_size` | DECIMAL(8,2) | Not null | Quantity per pack |
| `pack_unit` | ENUM | Not null | TABLET \| CAPSULE \| ML \| MG \| G \| STRIP \| VIAL \| AMPOULE \| SACHET \| TUBE \| BOTTLE |
| `schedule` | ENUM | Not null | OTC \| H \| H1 \| X |
| `hsn_code` | CHAR(8) | Not null | 8-digit HSN code |
| `gst_pct` | SMALLINT | Not null, CHECK IN (5,12,18) | GST rate |
| `mrp` | DECIMAL(10,2) | Not null, positive | Maximum retail price (INR) |
| `mrp_ceiling` | DECIMAL(10,2) | Nullable | Admin-set price ceiling; NULL if no ceiling |
| `is_rx_only` | BOOLEAN | Not null | Whether prescription is required |
| `is_banned` | BOOLEAN | Not null, default false | Whether medicine is banned platform-wide |
| `ban_reason` | TEXT | Nullable | Reason for ban |
| `monthly_demand` | INTEGER | Not null, default 0 | Trailing 30-day order count (computed) |
| `mapped_pharmacy_count` | INTEGER | Not null, default 0 | Count of pharmacies mapping this SKU (computed) |
| `substitutes` | UUID[] | Not null, default {} | Array of substitute MedicineMaster IDs |
| `description` | TEXT | Nullable | Medicine description / usage info |
| `created_by` | UUID | FK ? User.id | Admin who created |
| `created_at` | TIMESTAMPTZ | Not null, default now() | Creation timestamp |
| `updated_at` | TIMESTAMPTZ | Not null | Last update timestamp |
| UNIQUE | - | (salt_composition, manufacturer, form, pack_size, pack_unit) | Uniqueness constraint |

---

## Acceptance Criteria

- [ ] **Given** POST `/api/v1/admin/catalogue` with a valid medicine payload, **then** a `MedicineMaster` record is created, `is_rx_only` is forced to `true` if `schedule` is H, H1, or X regardless of the submitted value, and an `AuditLog` entry with `action=MEDICINE_CREATED` is written.
- [ ] **Given** a medicine with `schedule=X` is created and a pharmacy tries to add it to their online store, **then** the system returns `SCHEDULE_X_NOT_AVAILABLE_ONLINE`.
- [ ] **Given** POST `/api/v1/admin/catalogue` with the same `salt_composition + manufacturer + form + pack_size + pack_unit` as an existing record, **then** HTTP 409 `DUPLICATE_MEDICINE` is returned.
- [ ] **Given** POST `/api/v1/admin/catalogue/:id/ban` with a valid reason, **then** `is_banned=true` is set, a background job runs to set `is_visible=false` on all `PharmacyCatalogueMapping` records for this medicine within 30 seconds, and the medicine is excluded from search results immediately.
- [ ] **Given** GET `/api/v1/admin/catalogue/:id`, **then** the response includes `stocking_pharmacies`, `substitutes`, `demand_stats`, and `mrp_ceiling` (null if not set).
- [ ] **Given** PATCH `/api/v1/admin/catalogue/:id` with `gst_pct=7`, **then** HTTP 400 `INVALID_GST_RATE` is returned.
- [ ] **Given** `monthly_demand` is submitted in a POST or PATCH body, **then** it is ignored (read-only computed field); the response always shows the server-computed value.
- [ ] **Given** GET `/api/v1/admin/catalogue?is_banned=true`, **then** only banned medicines are returned, each with `ban_reason` populated.

---

## Dependencies

- STORY-005-002 - Category management (category_id must exist before creating medicines)
- STORY-005-005 - Pharmacy catalogue mapping (ban triggers is_visible update on all mappings)
- EPIC-008 - Orders (monthly_demand computed from order data)
- Infrastructure: HSN code reference table (must be seeded at deployment)
- Infrastructure: PostgreSQL full-text + trigram index on name, salt_composition, manufacturer

---

## Notes

- Full-text search index: Create `GIN` index on `to_tsvector('english', name || ' ' || salt_composition || ' ' || manufacturer)` for the medicine list and search endpoints.
- Also create a `pg_trgm` trigram `GIN` index on `name` and `salt_composition` for fuzzy autocomplete.
- The ban background job (hiding all pharmacy mappings) should be idempotent and retryable. Use a `MedicineBanJob` status table to track progress and avoid re-hiding already-hidden items.
- `monthly_demand` and `mapped_pharmacy_count` are updated by a nightly batch job at 02:00 IST. They are informational and not used in order flow calculations.
- Schedule classification reference: OTC = no restriction; H = prescription (standard antibiotics, antifungals); H1 = prescription (dangerous drugs requiring special pharmacist register - sulphonamides, chloramphenicol, etc.); X = narcotic/psychotropic (separate triplicate Rx, NDPS Act compliance).
- MRP should be sourced from the National Pharmaceutical Pricing Authority (NPPA) price list for essential medicines (NLEM). Future enhancement: automated NPPA sync.
