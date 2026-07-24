# STORY-002: Smart Pharmacy Auto-Selection Engine

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-002 |
| **Epic** | EPIC-010 - Order Management |
| **Priority** | P0 |
| **Complexity** | M |
| **Status** | Draft |

---

## Overview

This story defines the smart pharmacy selection engine that automatically picks the best pharmacy for a customer when they add their first item to an empty cart. The scoring algorithm weighs four factors - distance (60%), fill rate (20%), pharmacy rating (10%), and delivery ETA (10%) - to surface the highest-quality fulfilment option closest to the customer. The engine also powers the pharmacy storefront discovery API (customer home screen nearby pharmacies list), availability checks, and the ranked pharmacy picker when a customer wants to manually browse alternatives. Only open pharmacies that stock the requested medicine within the configured radius are considered.

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| `customer` | Full access | Smart-select, browse nearby pharmacies, check availability |
| `admin_operations` | Read-only | View storefront for operational monitoring |
| `pharmacy_owner` | None (own profile) | Pharmacy updates own profile via EPIC-006 |

---

## Business Rules

1. **Scoring formula:** Each eligible pharmacy is scored as: `score = (0.60 - distance_score) + (0.20 - fill_rate_score) + (0.10 - rating_score) + (0.10 - eta_score)`. All component scores are normalised to [0, 1] - lower distance and lower ETA yield higher scores. `distance_score = 1 - (distance_km / max_radius_km)`. `fill_rate_score = fill_rate_7d / 100`. `rating_score = pharmacy_rating / 5`. `eta_score = 1 - (delivery_eta_minutes / 60)`.
2. **Eligibility filter before scoring:** Only pharmacies that pass ALL of: (a) `is_open = true`, (b) `is_online = true` (accepts online orders), (c) `is_banned = false`, (d) stocks the requested medicine with `quantity_available > 0`, (e) within `radius_km` (default 5km for smart-select, 3km for Rx quote broadcast) are scored.
3. **Unavailability handling:** If no pharmacy passes the eligibility filter, the response returns `available: false` with `message: "Currently unavailable near you"`. The cart is NOT created and `pharmacy_id` remains null.
4. **Delivery ETA computation:** `delivery_eta_minutes = (distance_km / avg_speed_kmh) - 60 + avg_prep_time_minutes`. `avg_speed_kmh = 25` (city traffic default). `avg_prep_time_minutes` = pharmacy's historical rolling 7-day average packing time (default 10 min if no history).
5. **Storefront visibility:** `GET /api/v1/pharmacies/:id/products` returns only products where `is_online_visible = true`, `quantity_available > 0`, and the product is not from a recalled batch (`is_banned = false`).
6. **Availability check:** `POST /api/v1/pharmacies/availability-check` is used at checkout and during cart review to validate that all items remain in stock. Returns a split of `available` and `unavailable` medicine IDs at the specified pharmacy.
7. **Radius defaults:** Smart-select uses 5km radius. Nearby pharmacies list uses default 3km (customer-configurable up to 10km). Rx quote broadcast uses 3km (fixed, not configurable).
8. **Nearby pharmacy list (home screen):** Returns only open pharmacies, sorted by distance. Each entry includes current_offer (free text badge), categories_available, ETA, and distance. Used for pre-browse before adding any items.

---

## API Endpoints

### 1. Smart-Select Pharmacy for a Medicine

```POST /api/v1/cart/smart-select```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 30 req/min

**Request Body:**
```json
{
  "medicine_id": "prod_01J3KP7VOOO555",
  "lat": 12.9345,
  "lng": 77.6125
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `medicine_id` | UUID | Yes | Medicine to find |
| `lat` | number | Yes | Customer latitude |
| `lng` | number | Yes | Customer longitude |

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "available": true,
    "selected_pharmacy": {
      "id": "ph_01J3KP7VFFF666",
      "name": "Sai Medicals",
      "area": "Koramangala, Bengaluru",
      "distance_km": 1.2,
      "delivery_eta_minutes": 18,
      "is_open": true,
      "rating": 4.6,
      "score": 0.874
    },
    "alternatives": [
      {
        "id": "ph_01J3KP7VQQQ777",
        "name": "Apollo Pharmacy",
        "area": "BTM Layout, Bengaluru",
        "distance_km": 2.1,
        "delivery_eta_minutes": 25,
        "is_open": true,
        "rating": 4.4,
        "score": 0.791
      }
    ]
  }
}
```

**Response when unavailable:**
```json
{
  "success": true,
  "data": {
    "available": false,
    "message": "Currently unavailable near you",
    "selected_pharmacy": null,
    "alternatives": []
  }
}
```

---

### 2. List Nearby Pharmacies

```GET /api/v1/pharmacies/nearby```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 30 req/min

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `lat` | number | Yes | Customer latitude |
| `lng` | number | Yes | Customer longitude |
| `radius_km` | number | 3 | Search radius (max 10) |
| `limit` | integer | 10 | Number of results (max 30) |

**Response `200 OK`:**
```json
{
  "success": true,
  "data": [
    {
      "id": "ph_01J3KP7VFFF666",
      "name": "Sai Medicals",
      "area": "Koramangala, Bengaluru",
      "distance_km": 1.2,
      "delivery_eta_minutes": 18,
      "is_open": true,
      "rating": 4.6,
      "review_count": 312,
      "current_offer": "Free delivery on orders above ?199",
      "logo_url": "https://cdn.nammamedmate.com/pharmacies/sai-medicals.png",
      "categories_available": ["Prescription", "OTC", "Baby Care", "Personal Care"],
      "items_count": 1240
    }
  ],
  "meta": {
    "total": 7,
    "radius_km": 3
  }
}
```

---

### 3. Get Pharmacy Storefront

```GET /api/v1/pharmacies/:id```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 60 req/min

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "id": "ph_01J3KP7VFFF666",
    "name": "Sai Medicals",
    "area": "Koramangala, Bengaluru",
    "distance_km": 1.2,
    "delivery_eta_minutes": 18,
    "is_open": true,
    "rating": 4.6,
    "review_count": 312,
    "logo_url": "https://cdn.nammamedmate.com/pharmacies/sai-medicals.png",
    "current_offer": "Free delivery on orders above ?199",
    "categories_available": ["Prescription", "OTC", "Baby Care"],
    "items_count": 1240,
    "open_hours": "08:00 AM - 10:00 PM",
    "address": "12, 80 Feet Road, Koramangala 4th Block"
  }
}
```

---

### 4. Get Pharmacy Products

```GET /api/v1/pharmacies/:id/products```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 30 req/min

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `category` | string | - | Filter by category |
| `search` | string | - | Search by medicine name |
| `page` | integer | 1 | Pagination |
| `limit` | integer | 20 | Items per page (max 100) |

**Response `200 OK`:**
```json
{
  "success": true,
  "data": [
    {
      "product_id": "prod_01J3KP7VOOO555",
      "name": "Metformin 500mg (Glycomet)",
      "brand": "USV Ltd",
      "category": "Antidiabetics",
      "pack_size": "10 tablets",
      "mrp": 28.50,
      "selling_price": 25.65,
      "discount_pct": 10,
      "is_rx_required": true,
      "quantity_available": 200,
      "image_url": "https://cdn.nammamedmate.com/products/metformin-500.jpg"
    }
  ],
  "meta": {
    "page": 1,
    "limit": 20,
    "total": 1,
    "total_pages": 1
  }
}
```

---

### 5. Availability Check

```POST /api/v1/pharmacies/availability-check```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 20 req/min

**Request Body:**
```json
{
  "pharmacy_id": "ph_01J3KP7VFFF666",
  "medicine_ids": [
    "prod_01J3KP7VOOO555",
    "prod_01J3KP7VPPP456"
  ]
}
```

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "pharmacy_id": "ph_01J3KP7VFFF666",
    "pharmacy_name": "Sai Medicals",
    "is_open": true,
    "available": [
      { "medicine_id": "prod_01J3KP7VOOO555", "name": "Metformin 500mg", "quantity_available": 200, "price": 25.65 }
    ],
    "unavailable": [
      { "medicine_id": "prod_01J3KP7VPPP456", "name": "Glipizide 5mg", "reason": "OUT_OF_STOCK" }
    ]
  }
}
```

---

## Data Models

### PharmacyScore (ephemeral, not persisted)

| Field | Type | Description |
|-------|------|-------------|
| `pharmacy_id` | UUID | Candidate pharmacy |
| `distance_km` | decimal | Haversine distance from customer |
| `distance_score` | decimal [0,1] | `1 - (distance / radius)` |
| `fill_rate_7d` | decimal | 7-day order fill rate % |
| `fill_rate_score` | decimal [0,1] | `fill_rate_7d / 100` |
| `pharmacy_rating` | decimal | Average rating (0-5) |
| `rating_score` | decimal [0,1] | `pharmacy_rating / 5` |
| `delivery_eta_minutes` | integer | Estimated delivery time |
| `eta_score` | decimal [0,1] | `1 - (eta / 60)`, floored at 0 |
| `total_score` | decimal | Weighted composite score |

---

## Acceptance Criteria

- [ ] **Given** a customer adds their first item to an empty cart, **when** the smart-select runs, **then** the pharmacy with the highest composite score (distance 60%, fill rate 20%, rating 10%, ETA 10%) is selected and returned.
- [ ] **Given** no pharmacy within 5km has the requested medicine in stock, **when** smart-select runs, **then** the response returns `available: false` and no cart is created.
- [ ] **Given** a pharmacy has `is_open = false`, **when** smart-select runs, **then** that pharmacy is excluded regardless of how high its score would otherwise be.
- [ ] **Given** `GET /api/v1/pharmacies/:id/products` is called, **when** the response is returned, **then** only products with `is_online_visible = true`, `quantity_available > 0`, and `is_banned = false` are included.
- [ ] **Given** `POST /api/v1/pharmacies/availability-check` is called with 3 medicine IDs, **when** 2 are in stock and 1 is out, **then** the response correctly categorises them into `available` (2) and `unavailable` (1) arrays.
- [ ] **Given** a pharmacy's `avg_prep_time_minutes` is unavailable (new pharmacy), **when** ETA is computed, **then** the default of 10 minutes is used.
- [ ] **Given** a customer requests `GET /api/v1/pharmacies/nearby` with `radius_km = 15` (exceeds max), **when** the request is made, **then** the API clamps the radius to 10km.
- [ ] **Given** a product batch is recalled (`is_banned = true`), **when** the pharmacy storefront products are fetched, **then** the recalled product does not appear in the response.

---

## Dependencies

| Dependency | Story / System | Notes |
|------------|---------------|-------|
| EPIC-010 STORY-001 - Cart management | Downstream | Smart-select called on first item add |
| EPIC-006 - Pharmacy inventory | Upstream | Stock quantity, price, `is_online_visible` |
| EPIC-006 - Pharmacy profile | Upstream | `is_open`, `is_online`, rating, address |
| Geospatial service (PostGIS) | Platform | Haversine distance queries |

---

## Notes

- Distance computation uses the Haversine formula. For production, a PostGIS `ST_Distance` query with a spatial index on `pharmacies.location` is recommended for sub-millisecond geo queries.
- `fill_rate_7d` is precomputed nightly by the analytics job and stored on the pharmacy record to avoid expensive real-time aggregation during the smart-select hot path.
- The scoring algorithm parameters (weights: 60/20/10/10) are configurable via an admin feature-flag system and can be A/B tested without a deployment.
