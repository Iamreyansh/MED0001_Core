# STORY-005-003: Medicine Search & Discovery

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-005-003 |
| **Epic** | EPIC-005 - Master Catalogue |
| **Priority** | P0 |
| **Complexity** | M |
| **Status** | Draft |

---

## Overview

This story covers the medicine search and discovery layer - enabling customers to find medicines by name, salt, brand, or description, and enabling pharmacy staff to search across the master catalogue and their own inventory simultaneously. Customer search is location-aware: results surface best pharmacy stock info (price, availability) from nearby open pharmacies. Pharmacy-scoped search helps staff look up medicines during order processing and catalogue mapping. Additional capabilities include public medicine detail pages, substitute medicine lookup, and a multi-medicine availability check for order pre-validation. Search is backed by PostgreSQL full-text + trigram indexes and autocomplete is served from Redis-cached suggestions.

---

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| Public / `customer` | Read | Full-text search and medicine detail (no auth required) |
| `pharmacy_owner` | Read | Pharmacy-scoped search across master + own inventory |
| `pharmacy_staff` | Read | Same as pharmacy_owner |
| `admin_super` | Read | Full catalogue search including banned medicines |
| `admin_operations` | Read | Full catalogue search |

---

## Business Rules

1. **Customer search excludes banned medicines**: Banned medicines (`is_banned=true`) are always excluded from customer-facing search results. Admin search can include banned medicines via `include_banned=true` parameter.
2. **Search index covers name, salt, manufacturer, and description**: Full-text search is performed on a concatenated tsvector of `name`, `salt_composition`, `manufacturer`, and `description`. Trigram similarity is used for fuzzy matching (handles typos). Results are ranked by relevance score first, then by distance (if lat/lng provided).
3. **Customer results show best pharmacy stock**: For each medicine in search results, the API returns a `best_pharmacy` object with the cheapest available stock from a nearby online pharmacy. "Nearby" is defined as pharmacies in the same zone as the customer's lat/lng. If no stock is nearby, `best_pharmacy=null`.
4. **Rx-only medicines show "Prescription required" tag**: Medicines with `is_rx_only=true` are shown in search results with an `rx_required: true` flag. Customers can view the detail and add to cart, but the order placement endpoint enforces prescription upload.
5. **Autocomplete returns top 10 suggestions**: The autocomplete endpoint (query param `autocomplete=true`) returns a maximum of 10 medicine name suggestions matching the prefix. Results are cached in Redis with a 10-minute TTL per query string. Minimum 2 characters required to trigger autocomplete.
6. **Pharmacy-scoped search merges master and custom SKUs**: The pharmacy dashboard search (`GET /pharmacy/catalogue/search`) returns matching results from both the master catalogue and the pharmacy's own custom POS SKUs (from `PharmacyInventory` table). Master results show `source=MASTER`; custom SKUs show `source=CUSTOM`.
7. **Availability check is synchronous**: POST `/catalogue/check-availability` checks real-time stock from `PharmacyCatalogueMapping.stock_quantity > 0` for the specified pharmacy. Returns per-medicine availability in a single call. Response time target: < 100ms.
8. **Search results respect price ceilings**: If a medicine has an active `mrp_ceiling`, the `best_pharmacy.price` returned must not exceed the ceiling. If a pharmacy's mapped price exceeds the ceiling, it is excluded from the best_pharmacy candidate set (not just flagged).
9. **Schedule X medicines show an in-store-only notice**: Schedule X medicines appear in search with `available_online: false` and a message directing customers to visit the pharmacy in person.
10. **Substitute search is one hop only**: GET `/catalogue/substitutes/:medicine_id` returns the direct substitutes array of the given medicine. It does not recursively fetch substitutes-of-substitutes.

---

## API Endpoints

### 1. Customer Medicine Search

```
GET /api/v1/catalogue/search
```

**Authentication:** None (public endpoint; optional JWT for personalisation)
**Rate Limit:** 120 req/min per IP; 300 req/min with valid JWT

**Query Parameters:**
| Param | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `q` | string | Yes | - | Search query, min 2 chars, max 200 chars |
| `category_id` | UUID | No | - | Filter by category |
| `schedule` | string | No | - | Filter: OTC \| H \| H1 \| X |
| `is_rx_only` | boolean | No | - | Filter by prescription requirement |
| `lat` | number | No | - | Customer latitude (WGS84); enables nearby pharmacy results |
| `lng` | number | No | - | Customer longitude (WGS84) |
| `pharmacy_id` | UUID | No | - | If provided, returns stock info for this pharmacy only |
| `autocomplete` | boolean | No | false | Return autocomplete suggestions instead of full results |
| `page` | integer | No | 1 | Page number |
| `limit` | integer | No | 20 | Results per page, max 50 |

**Success Response - 200 OK (full search):**
```json
{
  "success": true,
  "data": {
    "query": "augmentin",
    "results": [
      {
        "medicine_id": "uuid-v4",
        "name": "Augmentin 625 Tablet",
        "salt_composition": "Amoxicillin (500mg) + Clavulanic Acid (125mg)",
        "manufacturer": "GSK India",
        "category": { "name": "Antibiotics", "slug": "antibiotics" },
        "form": "TABLET",
        "pack_size": 10,
        "pack_unit": "TABLET",
        "schedule": "H",
        "is_rx_only": true,
        "rx_required": true,
        "available_online": true,
        "typical_mrp": 218.50,
        "relevance_score": 0.98,
        "best_pharmacy": {
          "pharmacy_id": "uuid-v4",
          "pharmacy_name": "Sharma Medical Store",
          "price": 215.00,
          "distance_km": 1.2,
          "estimated_delivery_minutes": 25,
          "in_stock": true
        }
      }
    ],
    "total_results": 3,
    "did_you_mean": null
  },
  "meta": {
    "page": 1,
    "limit": 20,
    "total": 3,
    "total_pages": 1
  }
}
```

**Success Response - 200 OK (autocomplete: `autocomplete=true`):**
```json
{
  "success": true,
  "data": {
    "query": "augm",
    "suggestions": [
      { "medicine_id": "uuid-v4", "name": "Augmentin 625 Tablet", "manufacturer": "GSK India" },
      { "medicine_id": "uuid-v4", "name": "Augmentin 1g Tablet", "manufacturer": "GSK India" },
      { "medicine_id": "uuid-v4", "name": "Augmentin 228.5mg Syrup", "manufacturer": "GSK India" }
    ]
  },
  "meta": { "cached": true }
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `QUERY_TOO_SHORT` | `q` is fewer than 2 characters |
| 400 | `QUERY_TOO_LONG` | `q` exceeds 200 characters |

---

### 2. Public Medicine Detail

```
GET /api/v1/catalogue/:id
```

**Authentication:** None (public endpoint)
**Rate Limit:** 120 req/min per IP

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| `id` | UUID | Medicine master ID |

**Query Parameters:**
| Param | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `lat` | number | No | - | Customer latitude for nearby pharmacy results |
| `lng` | number | No | - | Customer longitude |

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "medicine_id": "uuid-v4",
    "name": "Augmentin 625 Tablet",
    "salt_composition": "Amoxicillin (500mg) + Clavulanic Acid (125mg)",
    "manufacturer": "GSK India",
    "category": { "name": "Antibiotics", "slug": "antibiotics" },
    "form": "TABLET",
    "pack_size": 10,
    "pack_unit": "TABLET",
    "schedule": "H",
    "is_rx_only": true,
    "rx_required": true,
    "available_online": true,
    "description": "Augmentin 625 Tablet is a combination antibiotic used to treat bacterial infections of the ear, nose, throat, lung, skin, urinary tract, etc.",
    "typical_mrp": 218.50,
    "stocking_pharmacies_nearby": [
      {
        "pharmacy_id": "uuid-v4",
        "pharmacy_name": "Sharma Medical Store",
        "price": 215.00,
        "in_stock": true,
        "distance_km": 1.2,
        "estimated_delivery_minutes": 25
      },
      {
        "pharmacy_id": "uuid-v4",
        "pharmacy_name": "Wellness Plus Pharmacy",
        "price": 218.50,
        "in_stock": true,
        "distance_km": 2.8,
        "estimated_delivery_minutes": 40
      }
    ],
    "substitutes": [
      {
        "medicine_id": "uuid-v4",
        "name": "Mox CV 625 Tablet",
        "manufacturer": "Cipla",
        "typical_mrp": 198.00
      }
    ]
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 404 | `MEDICINE_NOT_FOUND` | Medicine ID not found |
| 410 | `MEDICINE_BANNED` | Medicine is banned and not publicly accessible |

---

### 3. Pharmacy-Scoped Search

```
GET /api/v1/pharmacy/catalogue/search
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 120 req/min

**Query Parameters:**
| Param | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `q` | string | Yes | - | Search query, min 2 chars |
| `source` | string | No | ALL | Filter: MASTER \| CUSTOM \| ALL |
| `in_stock_only` | boolean | No | false | Only return medicines with stock_quantity > 0 in own inventory |
| `page` | integer | No | 1 | Page number |
| `limit` | integer | No | 20 | Results per page, max 50 |

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "query": "paracetamol",
    "pharmacy_id": "uuid-v4",
    "results": [
      {
        "source": "MASTER",
        "medicine_id": "uuid-v4",
        "name": "Crocin 500mg Tablet",
        "salt_composition": "Paracetamol (500mg)",
        "manufacturer": "GSK India",
        "form": "TABLET",
        "pack_size": 20,
        "schedule": "OTC",
        "is_rx_only": false,
        "master_mrp": 22.50,
        "pharmacy_price": 21.00,
        "stock_quantity": 150,
        "mapping_id": "uuid-v4",
        "is_mapped": true,
        "is_visible": true
      },
      {
        "source": "CUSTOM",
        "medicine_id": null,
        "custom_sku_id": "uuid-v4",
        "name": "Paracetamol Generic 500mg",
        "salt_composition": "Paracetamol (500mg)",
        "form": "TABLET",
        "pack_size": 10,
        "pharmacy_price": 12.00,
        "stock_quantity": 80,
        "is_mapped": false,
        "is_visible": false,
        "note": "Custom SKU - not available on online store"
      }
    ]
  },
  "meta": {
    "page": 1,
    "limit": 20,
    "total": 2,
    "total_pages": 1
  }
}
```

---

### 4. Get Substitute Medicines

```
GET /api/v1/catalogue/substitutes/:medicine_id
```

**Authentication:** None (public endpoint)
**Rate Limit:** 120 req/min per IP

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| `medicine_id` | UUID | Medicine master ID to find substitutes for |

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "medicine_id": "uuid-v4",
    "medicine_name": "Augmentin 625 Tablet",
    "substitutes": [
      {
        "medicine_id": "uuid-v4",
        "name": "Mox CV 625 Tablet",
        "salt_composition": "Amoxicillin (500mg) + Clavulanic Acid (125mg)",
        "manufacturer": "Cipla",
        "form": "TABLET",
        "pack_size": 10,
        "schedule": "H",
        "is_rx_only": true,
        "typical_mrp": 198.00
      }
    ]
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 404 | `MEDICINE_NOT_FOUND` | medicine_id not found |

---

### 5. Check Medicine Availability at Pharmacy

```
POST /api/v1/catalogue/check-availability
```

**Authentication:** None (public endpoint); optional JWT for rate limit increase
**Rate Limit:** 60 req/min per IP; 200 req/min with JWT

**Request Body (application/json):**
```json
{
  "medicine_ids": ["uuid-v4", "uuid-v4"],
  "pharmacy_id": "string (UUID) - required, pharmacy to check stock at"
}
```

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "pharmacy_id": "uuid-v4",
    "pharmacy_name": "Sharma Medical Store",
    "pharmacy_is_online": true,
    "checked_at": "2026-07-24T00:00:00Z",
    "results": [
      {
        "medicine_id": "uuid-v4",
        "name": "Augmentin 625 Tablet",
        "in_stock": true,
        "stock_quantity": 48,
        "pharmacy_price": 215.00,
        "is_rx_only": true
      },
      {
        "medicine_id": "uuid-v4",
        "name": "Crocin 500mg Tablet",
        "in_stock": false,
        "stock_quantity": 0,
        "pharmacy_price": 21.00,
        "is_rx_only": false
      }
    ]
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `MEDICINE_IDS_REQUIRED` | `medicine_ids` array is empty |
| 400 | `TOO_MANY_MEDICINES` | More than 50 medicine IDs in a single request |
| 404 | `PHARMACY_NOT_FOUND` | `pharmacy_id` not found or not ACTIVE |

---

## Data Models

### SearchIndex (PostgreSQL configuration - not a table)

| Index | Type | Columns | Purpose |
|-------|------|---------|---------|
| `idx_medicine_fts` | GIN (tsvector) | `to_tsvector('english', name \|\| ' ' \|\| salt_composition \|\| ' ' \|\| manufacturer \|\| ' ' \|\| COALESCE(description, ''))` | Full-text search |
| `idx_medicine_name_trgm` | GIN (pg_trgm) | `name` | Fuzzy name search |
| `idx_medicine_salt_trgm` | GIN (pg_trgm) | `salt_composition` | Fuzzy salt search |

### AutocompleteSuggestion (Redis cached)

| Key Pattern | Value | TTL |
|-------------|-------|-----|
| `autocomplete:{prefix}` | JSON array of top-10 medicine suggestions | 10 minutes |
| `medicine_detail:{medicine_id}` | Serialised public medicine detail | 5 minutes |

---

## Acceptance Criteria

- [ ] **Given** GET `/api/v1/catalogue/search?q=augmentin&lat=12.93&lng=77.62`, **then** the response includes matching medicines with `relevance_score`, `best_pharmacy` with price and distance from the provided coordinates, and banned medicines are excluded.
- [ ] **Given** `q=paracet` (5 characters, matches "paracetamol" via trigram), **then** medicines matching "paracetamol" appear in results with an appropriate relevance score.
- [ ] **Given** `autocomplete=true&q=aug`, **then** up to 10 autocomplete suggestions are returned, the response is served from Redis cache on subsequent identical queries, and the response time is < 80ms (p95).
- [ ] **Given** GET `/api/v1/catalogue/:id` for a banned medicine, **then** HTTP 410 `MEDICINE_BANNED` is returned.
- [ ] **Given** GET `/api/v1/catalogue/:id?lat=12.93&lng=77.62` for an active medicine, **then** `stocking_pharmacies_nearby` lists online pharmacies in the customer's zone with price, distance, and estimated delivery time, sorted by price ascending.
- [ ] **Given** a medicine in search results has `schedule=X`, **then** `available_online=false` and a note directing in-store visit is returned; the medicine is shown but cannot be added to an online cart.
- [ ] **Given** POST `/api/v1/catalogue/check-availability` with `pharmacy_id` and 3 `medicine_ids`, **then** the response returns per-medicine `in_stock`, `stock_quantity`, and `pharmacy_price` in < 100ms (p95).
- [ ] **Given** GET `/api/v1/pharmacy/catalogue/search?q=crocin&source=ALL`, **then** the response includes both master-catalogue results (with `source=MASTER`, `is_mapped`, and pharmacy price) and custom POS SKUs (with `source=CUSTOM`) for the authenticated pharmacy.

---

## Dependencies

- STORY-005-001 - Medicine Master CRUD (medicine records are the search source)
- STORY-005-005 - Pharmacy Catalogue Mapping (stock info for best_pharmacy in customer search)
- EPIC-009 - Zone Management (zone-based nearby pharmacy filtering)
- Infrastructure: PostgreSQL pg_trgm extension and GIN indexes
- Infrastructure: Redis - autocomplete cache, medicine detail cache
- Infrastructure: Geocoding service - convert lat/lng to zone for nearby filtering

---

## Notes

- Customer search query flow: (1) check Redis autocomplete cache, (2) if miss, run PostgreSQL FTS + trigram query ranked by `ts_rank + similarity`, (3) for top 20 results, fetch best_pharmacy from `PharmacyCatalogueMapping` JOIN `Pharmacy` WHERE `Pharmacy.is_online=true AND Pharmacy.zone_id IN (nearby zones) AND mapping.stock_quantity > 0`, ORDER BY price ASC LIMIT 1 per medicine.
- "Nearby zones" algorithm: given lat/lng, find the zone whose polygon contains the coordinate (PostGIS `ST_Contains`). If no exact match, use the 3 nearest zone centroids.
- `did_you_mean` field: if search returns 0 results, run a secondary query using trigram similarity on the query string against medicine names. Return the top suggestion if similarity > 0.4.
- Pharmacy-scoped search must return results in < 200ms (p95). Use a combined query that UNION ALL the master FTS results (filtered by pharmacy's mapped SKUs) and the custom POS SKUs, deduplicated by `salt_composition + form + pack_size`.
- Rate limiting for the public search endpoint uses a sliding window algorithm in Redis. Unauthenticated IPs are limited to 120 req/min; authenticated users to 300 req/min.
