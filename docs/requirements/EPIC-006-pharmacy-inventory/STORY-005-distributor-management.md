# STORY-005: Distributor Management - Supplier Directory & Price Comparison

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-005 |
| **Epic** | EPIC-006 - Pharmacy Inventory |
| **Priority** | P1 |
| **Complexity** | M |
| **Status** | Draft |

---

## Overview

This story provides the Growth-plan pharmacy with a complete supplier/distributor directory and a price comparison engine. Pharmacists can register their distributors with contact, GSTIN, and drug-licence details, track outstanding payables, and compare landed costs across suppliers for each medicine. The preferred-source flag enables the reorder suggestion engine (STORY-006) to automatically recommend the cheapest or preferred distributor when raising purchase orders.

---

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| `pharmacy_owner` | Full read + write | Create, edit, deactivate distributors; set preferred sources |
| `pharmacy_staff` | Read-only | View distributor list and supply details |
| `admin_finance` | Read-only | Cross-pharmacy distributor and payable data |
| `admin_compliance` | Read-only | GSTIN and drug-licence compliance checks |
| `customer` | No access | Not applicable |

---

## Business Rules

1. **Growth plan gating.** The entire distributor management module (including price comparison) is only accessible to pharmacies on the Growth or Pro plan. Free and Starter pharmacies receive a 403 `PLAN_FEATURE_LOCKED` response on all endpoints in this story.
2. **Soft delete only.** Distributors are never permanently deleted. `DELETE /distributors/:id` sets `is_active = false`. Deactivated distributors are hidden from the active list but their purchase history, GRN records, and supply-list entries are fully preserved.
3. **Outstanding payable computation.** `outstanding_payable` is computed as the sum of all finalized GRN `grand_total` minus recorded repayments against that distributor. It is not a stored field; it is always computed at query time from the purchase ledger.
4. **GSTIN and drug licence are recommended, not mandatory.** However, if `gstin` is provided, it must match the GST GSTIN format: `^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z]{1}[1-9A-Z]{1}Z[0-9A-Z]{1}$`.
5. **Price comparison is Growth+ only.** The `GET /distributors/price-compare` endpoint is plan-gated and returns 403 for Free/Starter.
6. **Preferred source per product.** The `PATCH /supply-list/:product_id/set-preferred` call sets `is_preferred_source = true` for one distributor-product pair and sets all other distributors for that product to `is_preferred_source = false`. Only one distributor can be preferred per product at a time.
7. **Effective landed cost** = `purchase_price - (free_goods_value / total_units)`. The supply-list endpoint calculates and returns this figure alongside raw `purchase_price`.
8. **Distributor deactivation does not affect open purchase orders or GRNs.** Existing open POs tied to a deactivated distributor can still be processed. New POs cannot be raised for deactivated distributors.

---

## API Endpoints

### 1. List Distributors

```
GET /api/v1/pharmacy/distributors
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 60 req/min
**Plan:** Growth+

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `is_active` | boolean | `true` | Filter active/inactive |
| `q` | string | - | Search by name, phone, GSTIN |
| `page` | integer | `1` | Page number |
| `limit` | integer | `20` | Items per page |

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "kpi": {
      "distributor_count": 8,
      "products_sourced": 342,
      "outstanding_payable": 128500.00,
      "on_credit_count": 5
    },
    "distributors": [
      {
        "id": "uuid",
        "firm_name": "Medico Pharma Distributors",
        "contact_name": "Ramesh Kumar",
        "phone": "+919876543210",
        "email": "ramesh@medicopharma.in",
        "gstin": "27AABCM1234A1Z5",
        "drug_licence_number": "DL-MH-2024-00123",
        "outstanding_payable": 28500.00,
        "on_credit": true,
        "credit_limit": 100000.00,
        "payment_terms_days": 30,
        "is_active": true,
        "last_purchase_date": "2026-07-22"
      }
    ]
  },
  "meta": {
    "page": 1,
    "limit": 20,
    "total": 8
  }
}
```

---

### 2. Add Distributor

```
POST /api/v1/pharmacy/distributors
```

**Authentication:** Bearer JWT - `pharmacy_owner`
**Rate Limit:** 20 req/min
**Plan:** Growth+

**Request Body (application/json):**

```json
{
  "firm_name": "string max 200 - required",
  "contact_name": "string max 100 - optional",
  "phone": "string E.164 format - required",
  "email": "string valid email - optional",
  "gstin": "string 15 chars - optional, validated",
  "drug_licence_number": "string max 50 - optional",
  "address": "string max 500 - optional",
  "payment_terms_days": "integer ? 0 - optional, default 0 (immediate)",
  "credit_limit": "number ? 0 - optional, default 0",
  "is_active": "boolean - optional, default true"
}
```

**Success Response - 201 Created:**

```json
{
  "success": true,
  "data": {
    "id": "uuid",
    "firm_name": "Medico Pharma Distributors",
    "phone": "+919876543210",
    "gstin": "27AABCM1234A1Z5",
    "payment_terms_days": 30,
    "credit_limit": 100000.00,
    "is_active": true,
    "created_at": "2026-07-24T11:00:00Z"
  }
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `INVALID_GSTIN_FORMAT` | GSTIN does not match regex |
| 400 | `INVALID_PHONE` | Phone number invalid |
| 403 | `PLAN_FEATURE_LOCKED` | Pharmacy not on Growth+ plan |
| 409 | `DISTRIBUTOR_PHONE_EXISTS` | Another active distributor with same phone |

---

### 3. Update Distributor

```
PATCH /api/v1/pharmacy/distributors/:id
```

**Authentication:** Bearer JWT - `pharmacy_owner`
**Rate Limit:** 30 req/min
**Plan:** Growth+

**Request Body (application/json):** Same optional fields as POST.

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "id": "uuid",
    "firm_name": "Medico Pharma Distributors",
    "updated_at": "2026-07-24T11:10:00Z"
  }
}
```

---

### 4. Deactivate Distributor

```
DELETE /api/v1/pharmacy/distributors/:id
```

**Authentication:** Bearer JWT - `pharmacy_owner`
**Rate Limit:** 10 req/min
**Plan:** Growth+

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "id": "uuid",
    "is_active": false,
    "deactivated_at": "2026-07-24T11:15:00Z"
  }
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 404 | `DISTRIBUTOR_NOT_FOUND` | ID not found |

---

### 5. Distributor Supply List

```
GET /api/v1/pharmacy/distributors/:id/supply-list
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 30 req/min
**Plan:** Growth+

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `q` | string | - | Search by product name |
| `page` | integer | `1` | Page |
| `limit` | integer | `20` | Items per page |

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "distributor": { "id": "uuid", "firm_name": "Medico Pharma Distributors" },
    "supply_items": [
      {
        "product_id": "uuid",
        "product_name": "Paracetamol 500mg Tab",
        "manufacturer": "Cipla Ltd",
        "purchase_price": 13.00,
        "scheme_free_qty": "1 free on 10",
        "effective_landed_cost": 11.82,
        "mrp": 22.50,
        "margin_pct": 47.0,
        "price_rank": 1,
        "is_preferred_source": true
      }
    ]
  },
  "meta": { "page": 1, "limit": 20, "total": 84 }
}
```

---

### 6. Price Comparison (Cross-Distributor)

```
GET /api/v1/pharmacy/distributors/price-compare
```

**Authentication:** Bearer JWT - `pharmacy_owner`
**Rate Limit:** 20 req/min
**Plan:** Growth+

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `only_multi_source` | boolean | `false` | Only show products with 2+ distributors |
| `q` | string | - | Filter by product name |
| `page` | integer | `1` | Page |
| `limit` | integer | `20` | Items |

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "products": [
      {
        "product_id": "uuid",
        "product_name": "Paracetamol 500mg Tab",
        "manufacturer": "Cipla Ltd",
        "distributor_prices": [
          {
            "distributor_id": "uuid",
            "distributor_name": "Medico Pharma",
            "purchase_price": 13.00,
            "effective_landed_cost": 11.82,
            "mrp": 22.50,
            "is_preferred_source": true,
            "price_rank": 1
          },
          {
            "distributor_id": "uuid",
            "distributor_name": "Apollo Pharmacy Dist.",
            "purchase_price": 14.50,
            "effective_landed_cost": 14.50,
            "mrp": 22.50,
            "is_preferred_source": false,
            "price_rank": 2
          }
        ]
      }
    ]
  },
  "meta": { "page": 1, "limit": 20, "total": 62 }
}
```

---

### 7. Set Preferred Distributor for a Product

```
PATCH /api/v1/pharmacy/distributors/:id/supply-list/:product_id/set-preferred
```

**Authentication:** Bearer JWT - `pharmacy_owner`
**Rate Limit:** 30 req/min
**Plan:** Growth+

**Request Body:** None required.

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "distributor_id": "uuid",
    "product_id": "uuid",
    "is_preferred_source": true,
    "previous_preferred_distributor_id": "uuid"
  }
}
```

---

## Data Models

### Distributor

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID | PK | Unique distributor ID |
| `pharmacy_id` | UUID | FK ? Pharmacy, NOT NULL | Owning pharmacy |
| `firm_name` | VARCHAR(200) | NOT NULL | Company/firm name |
| `contact_name` | VARCHAR(100) | nullable | Primary contact person |
| `phone` | VARCHAR(20) | NOT NULL | Contact phone (E.164) |
| `email` | VARCHAR(255) | nullable | Contact email |
| `gstin` | VARCHAR(15) | nullable | GST Identification Number |
| `drug_licence_number` | VARCHAR(50) | nullable | Drug licence number |
| `address` | TEXT | nullable | Registered address |
| `payment_terms_days` | INTEGER | ? 0, default 0 | Credit period in days |
| `credit_limit` | NUMERIC(12,2) | ? 0, default 0 | Max credit allowed |
| `is_active` | BOOLEAN | NOT NULL, default true | Active/deactivated status |
| `created_at` | TIMESTAMPTZ | NOT NULL | Creation timestamp |
| `updated_at` | TIMESTAMPTZ | NOT NULL | Last update |

### DistributorSupplyItem

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID | PK | Unique supply mapping |
| `distributor_id` | UUID | FK ? Distributor, NOT NULL | Distributor |
| `product_id` | UUID | FK ? PharmacyProduct, NOT NULL | Product supplied |
| `pharmacy_id` | UUID | NOT NULL | Pharmacy context |
| `purchase_price` | NUMERIC(10,2) | > 0, NOT NULL | Last known PTR |
| `scheme_description` | VARCHAR(100) | nullable | e.g., "1 free on 10" |
| `is_preferred_source` | BOOLEAN | NOT NULL, default false | Preferred supplier flag |
| `last_purchased_at` | TIMESTAMPTZ | nullable | Last GRN date for this distributor/product |
| `updated_at` | TIMESTAMPTZ | NOT NULL | Last update |

---

## Acceptance Criteria

- [ ] Given a Free-plan pharmacy JWT, when `GET /api/v1/pharmacy/distributors` is called, then a 403 `PLAN_FEATURE_LOCKED` error is returned.
- [ ] Given `POST /distributors` with `gstin = "INVALIDGSTIN"`, then a 400 `INVALID_GSTIN_FORMAT` error is returned.
- [ ] Given `DELETE /distributors/:id` for a distributor with 50 GRN records, when deactivated, then existing GRN records remain intact and are still accessible.
- [ ] Given `PATCH /distributors/:id/supply-list/:product_id/set-preferred` for distributor A, then distributor B's `is_preferred_source` for that product is set to `false`.
- [ ] Given `GET /distributors/price-compare?only_multi_source=true`, then only products sourced from 2+ distributors appear in the response.
- [ ] Given `GET /distributors/:id/supply-list`, then each item returns `effective_landed_cost` correctly calculated as `purchase_price - (free_goods_value / total_units)`.
- [ ] Given a deactivated distributor, when `POST /purchases` is called with that `distributor_id`, then a 400 `DISTRIBUTOR_INACTIVE` error is returned.
- [ ] Given `GET /distributors` with `q="medico"`, then all distributors whose `firm_name` or `contact_name` contains "medico" (case-insensitive) are returned.

---

## Dependencies

- **EPIC-006 / STORY-004 (Purchase/GRN):** GRN records reference `distributor_id` and build `outstanding_payable`.
- **EPIC-006 / STORY-006 (Reorder Suggestions):** Preferred-source flag is consumed by the reorder engine.
- **Plan Gating Middleware:** All endpoints must validate Growth+ plan before business logic executes.

---

## Notes

- `DistributorSupplyItem` records are auto-created/updated when a GRN is finalized (STORY-004 save-and-stock). The `purchase_price` and `scheme_description` in the supply list always reflect the most recent GRN entry for that distributor-product pair.
- `price_rank` is a computed rank (1 = cheapest effective landed cost) and is not stored.
- Consider caching the `price-compare` response for 5 minutes given its compute-heavy cross-join nature.
