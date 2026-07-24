# STORY-005-002: Category & Schedule Management

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-005-002 |
| **Epic** | EPIC-005 - Master Catalogue |
| **Priority** | P0 |
| **Complexity** | S |
| **Status** | Draft |

---

## Overview

This story covers the management of medicine categories and schedule classification rules. Categories are the primary way medicines are organised for customer browsing on the home screen and in search - e.g., Antibiotics, Pain Relief, Vitamins, Diabetic Care. Each category has an icon, display order, and visibility toggle. Admin operations staff create, update, and reorder categories; soft deletion is allowed only if no active medicines are mapped. Schedule classification rules are informational reference documents (OTC, H, H1, X) that inform pharmacy staff about regulatory requirements. The category list is publicly accessible (no auth) for the customer app home screen.

---

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| `admin_super` | Full CRUD + reorder | Create, update, delete, reorder categories |
| `admin_operations` | Create, Update, Reorder | Cannot delete categories |
| `admin_compliance` | Read, Update (schedule rules) | Can view schedule rules and update regulatory notes |
| Public / `customer` | Read (public list) | Can fetch active categories for home screen (no auth) |
| `pharmacy_owner` | Read | Can browse categories for catalogue mapping |

---

## Business Rules

1. **Category slug must be URL-safe and unique**: The `slug` field must match `^[a-z0-9-]+$` (lowercase alphanumeric and hyphens only), maximum 100 characters, and must be unique across all categories (including soft-deleted ones). Slug changes are not allowed after creation to preserve bookmarked URLs. Returns `DUPLICATE_SLUG` or `INVALID_SLUG_FORMAT` on violation.
2. **Display order determines home screen ordering**: The `display_order` integer determines the left-to-right / top-to-bottom order of category tiles on the customer app home screen. Lower values appear first. Gaps in display_order are allowed. The bulk reorder endpoint assigns display_order values atomically.
3. **Soft deletion only when no active medicines are mapped**: DELETE `/catalogue/categories/:id` is rejected with `CATEGORY_HAS_ACTIVE_MEDICINES` if any `MedicineMaster` records have `category_id` pointing to this category and `is_banned=false`. Once all medicines are moved or banned, soft deletion succeeds.
4. **Soft-deleted categories are hidden from public list**: The public GET `/catalogue/categories` endpoint excludes soft-deleted categories. Admin GET includes them with an `is_deleted` flag.
5. **Category icon is hosted on CDN**: The `icon_url` field must be a valid HTTPS CDN URL. Direct file upload for icons uses the platform's media upload service (EPIC-001). Admin submits the CDN URL after upload.
6. **`is_visible=false` hides category from customer app**: Categories with `is_visible=false` are excluded from the public category list and from customer-facing search filters, but are still returned in admin views. Medicines within a hidden category remain searchable by name/salt.
7. **Reorder is atomic**: The bulk reorder endpoint PATCH `/catalogue/categories/reorder` updates all provided `{ id, display_order }` items in a single database transaction. If any ID is invalid, the entire operation is rolled back with `INVALID_CATEGORY_ID`.
8. **Schedule rules are read-only platform configuration**: The schedule rules document (GET `/admin/catalogue/schedule-rules`) is a structured regulatory reference. It can only be updated by `admin_compliance` via a separate admin configuration interface, not via the standard CRUD API.

---

## API Endpoints

### 1. List Categories (Public)

```
GET /api/v1/catalogue/categories
```

**Authentication:** None (public endpoint)
**Rate Limit:** 120 req/min per IP; cached with 5-minute CDN TTL

**Query Parameters:**
| Param | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `include_hidden` | boolean | No | false | Admin only: include `is_visible=false` categories (requires JWT) |

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "categories": [
      {
        "category_id": "uuid-v4",
        "name": "Antibiotics",
        "slug": "antibiotics",
        "icon_url": "https://cdn.example.com/categories/antibiotics.svg",
        "is_visible": true,
        "display_order": 1,
        "medicine_count": 2840
      },
      {
        "category_id": "uuid-v4",
        "name": "Pain Relief",
        "slug": "pain-relief",
        "icon_url": "https://cdn.example.com/categories/pain-relief.svg",
        "is_visible": true,
        "display_order": 2,
        "medicine_count": 1520
      }
    ]
  },
  "meta": {
    "total": 48,
    "cached_at": "2026-07-24T00:00:00Z"
  }
}
```

---

### 2. Create Category (Admin)

```
POST /api/v1/admin/catalogue/categories
```

**Authentication:** Bearer JWT - `admin_super`, `admin_operations`
**Rate Limit:** 20 req/min

**Request Body (application/json):**
```json
{
  "name": "string - required, 2-100 chars, display name",
  "slug": "string - required, lowercase alphanumeric and hyphens, unique",
  "icon_url": "string - required, valid HTTPS CDN URL, SVG or PNG",
  "is_visible": "boolean - optional, default true",
  "display_order": "integer - optional, positive integer; appended to end if omitted"
}
```

**Success Response - 201 Created:**
```json
{
  "success": true,
  "data": {
    "category_id": "uuid-v4",
    "name": "Diabetic Care",
    "slug": "diabetic-care",
    "icon_url": "https://cdn.example.com/categories/diabetic-care.svg",
    "is_visible": true,
    "display_order": 49,
    "medicine_count": 0,
    "created_at": "2026-07-24T00:00:00Z"
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `INVALID_SLUG_FORMAT` | Slug contains invalid characters |
| 400 | `INVALID_ICON_URL` | icon_url is not a valid HTTPS URL |
| 409 | `DUPLICATE_SLUG` | Slug already exists |
| 409 | `DUPLICATE_NAME` | Category name already exists |

---

### 3. Update Category (Admin)

```
PATCH /api/v1/admin/catalogue/categories/:id
```

**Authentication:** Bearer JWT - `admin_super`, `admin_operations`
**Rate Limit:** 20 req/min

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| `id` | UUID | Category ID |

**Request Body (application/json):**
```json
{
  "name": "string - optional, 2-100 chars",
  "icon_url": "string - optional, valid HTTPS CDN URL",
  "is_visible": "boolean - optional",
  "display_order": "integer - optional, positive integer"
}
```

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "category_id": "uuid-v4",
    "updated_fields": ["icon_url", "is_visible"],
    "updated_at": "2026-07-24T00:00:00Z"
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 403 | `FORBIDDEN` | Caller not admin_super or admin_operations |
| 404 | `CATEGORY_NOT_FOUND` | Category ID not found |

---

### 4. Delete Category (Admin - Soft Delete)

```
DELETE /api/v1/admin/catalogue/categories/:id
```

**Authentication:** Bearer JWT - `admin_super`
**Rate Limit:** 10 req/min

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| `id` | UUID | Category ID |

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "category_id": "uuid-v4",
    "deleted": true,
    "deleted_at": "2026-07-24T00:00:00Z"
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 403 | `FORBIDDEN` | Caller not admin_super |
| 404 | `CATEGORY_NOT_FOUND` | Category ID not found |
| 409 | `CATEGORY_HAS_ACTIVE_MEDICINES` | Active (non-banned) medicines still mapped to this category |

---

### 5. Bulk Reorder Categories (Admin)

```
PATCH /api/v1/admin/catalogue/categories/reorder
```

**Authentication:** Bearer JWT - `admin_super`, `admin_operations`
**Rate Limit:** 10 req/min

**Request Body (application/json):**
```json
{
  "items": [
    { "id": "uuid-v4", "display_order": 1 },
    { "id": "uuid-v4", "display_order": 2 },
    { "id": "uuid-v4", "display_order": 3 }
  ]
}
```

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "reordered_count": 3,
    "updated_at": "2026-07-24T00:00:00Z"
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `ITEMS_REQUIRED` | `items` array is empty |
| 400 | `INVALID_CATEGORY_ID` | One or more IDs not found; entire operation rolled back |
| 400 | `DUPLICATE_DISPLAY_ORDER` | Two items in the request share the same display_order |

---

### 6. Get Schedule Classification Rules (Admin Reference)

```
GET /api/v1/admin/catalogue/schedule-rules
```

**Authentication:** Bearer JWT - `admin_super`, `admin_operations`, `admin_compliance`, `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 60 req/min

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "schedules": [
      {
        "schedule": "OTC",
        "full_name": "Over-the-Counter",
        "description": "Medicines that can be sold without a prescription.",
        "prescription_required": false,
        "special_register_required": false,
        "examples": ["Paracetamol 500mg", "Antacid tablets", "Vitamin C supplements"],
        "regulatory_reference": "Drugs and Cosmetics Act, Schedule K"
      },
      {
        "schedule": "H",
        "full_name": "Schedule H",
        "description": "Prescription-only medicines including antibiotics, antihypertensives, and antidiabetics.",
        "prescription_required": true,
        "special_register_required": false,
        "examples": ["Augmentin 625", "Metformin 500mg", "Amlodipine 5mg"],
        "regulatory_reference": "Drugs and Cosmetics Act, Schedule H"
      },
      {
        "schedule": "H1",
        "full_name": "Schedule H1",
        "description": "Third-generation cephalosporins, carbapenems, and sulphonamides requiring pharmacist register.",
        "prescription_required": true,
        "special_register_required": true,
        "register_name": "Schedule H1 Dispensing Register",
        "examples": ["Ceftriaxone 1g Injection", "Imipenem-Cilastatin", "Chloramphenicol"],
        "regulatory_reference": "Drugs and Cosmetics (Amendment) Rules 2013, Schedule H1"
      },
      {
        "schedule": "X",
        "full_name": "Schedule X",
        "description": "Narcotic and psychotropic substances under NDPS Act. Triplicate Rx, patient ID verification. NOT available for online delivery.",
        "prescription_required": true,
        "prescription_type": "TRIPLICATE",
        "special_register_required": true,
        "register_name": "Narcotic Drugs Register",
        "patient_id_verification": true,
        "online_delivery_allowed": false,
        "examples": ["Morphine Sulphate", "Codeine Phosphate", "Alprazolam"],
        "regulatory_reference": "NDPS Act 1985, Narcotic Drugs and Psychotropic Substances Rules 1985"
      }
    ]
  },
  "meta": {}
}
```

---

## Data Models

### MedicineCategory

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID v4 | PK | Unique category identifier |
| `name` | VARCHAR(100) | Not null, unique | Display name |
| `slug` | VARCHAR(100) | Not null, unique | URL-safe slug |
| `icon_url` | TEXT | Not null | CDN URL of category icon |
| `is_visible` | BOOLEAN | Not null, default true | Customer app visibility |
| `display_order` | INTEGER | Not null, default (max+1) | Sort order for home screen |
| `is_deleted` | BOOLEAN | Not null, default false | Soft delete flag |
| `deleted_at` | TIMESTAMPTZ | Nullable | Soft deletion timestamp |
| `created_at` | TIMESTAMPTZ | Not null, default now() | Creation timestamp |
| `updated_at` | TIMESTAMPTZ | Not null | Last update timestamp |

*(Computed field `medicine_count` is derived via COUNT query or materialised view, not stored on the table.)*

---

## Acceptance Criteria

- [ ] **Given** GET `/api/v1/catalogue/categories` (no auth), **then** only categories with `is_visible=true` and `is_deleted=false` are returned, sorted by `display_order` ascending, with `medicine_count` per category.
- [ ] **Given** POST `/api/v1/admin/catalogue/categories` with `slug=pain-relief` and another category already has `slug=pain-relief`, **then** HTTP 409 `DUPLICATE_SLUG` is returned.
- [ ] **Given** PATCH `/api/v1/admin/catalogue/categories/reorder` with a valid `items` array, **then** all provided categories have their `display_order` updated atomically in a single transaction, and the public category list reflects the new order within 5 minutes (cache TTL).
- [ ] **Given** DELETE `/api/v1/admin/catalogue/categories/:id` on a category with 10 active (non-banned) medicines, **then** HTTP 409 `CATEGORY_HAS_ACTIVE_MEDICINES` is returned and no deletion occurs.
- [ ] **Given** DELETE `/api/v1/admin/catalogue/categories/:id` on a category with zero active medicines, **then** `is_deleted=true` and `deleted_at` are set; the category disappears from the public list immediately.
- [ ] **Given** PATCH `/catalogue/categories/reorder` contains an ID that does not exist, **then** the entire operation is rolled back (no display_order values are changed) and HTTP 400 `INVALID_CATEGORY_ID` is returned.
- [ ] **Given** `is_visible` is set to `false` on a category via PATCH, **then** the category no longer appears in the public GET `/catalogue/categories` response, but medicines in that category are still discoverable via name/salt search.
- [ ] **Given** GET `/api/v1/admin/catalogue/schedule-rules`, **then** all four schedules (OTC, H, H1, X) are returned with `prescription_required`, `special_register_required`, `online_delivery_allowed`, and `regulatory_reference` for each.

---

## Dependencies

- STORY-005-001 - Medicine Master CRUD (medicines reference category_id from this story)
- EPIC-001 / Media Upload Service - CDN URL generation for category icon uploads
- Infrastructure: Redis - category list cache (5-minute TTL); invalidated on create/update/delete/reorder

---

## Notes

- Category list cache key: `catalogue:categories:public`. Invalidate on any create, update, delete, or reorder. The public endpoint should read from cache first and fall back to DB on cache miss.
- `display_order` values do not need to be contiguous. The reorder endpoint simply sets the provided integers; gaps are fine. Recommend always sending the full ordered list to avoid ordering ambiguity.
- A default set of 48 categories is seeded at platform launch via a database migration/seed file. Categories include: Antibiotics, Antifungals, Antacids, Pain Relief, Vitamins & Supplements, Diabetic Care, Blood Pressure, Cardiac Care, Thyroid, Allergy & Sinus, Skin Care, Eye & Ear Drops, Women's Health, Men's Health, Baby Care, Surgical Supplies, etc.
- The `medicine_count` on the category list can be served from a Redis counter (incremented/decremented on medicine creation/banning) or from a nightly materialised count, depending on accuracy requirements.
