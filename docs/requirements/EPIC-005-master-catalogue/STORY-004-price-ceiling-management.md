# STORY-005-004: Price Ceiling Management

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-005-004 |
| **Epic** | EPIC-005 - Master Catalogue |
| **Priority** | P1 |
| **Complexity** | S |
| **Status** | Draft |

---

## Overview

This story covers admin-controlled price ceiling management for essential medicines. Admin super users can set an MRP ceiling on specific master medicines, enforcing that no pharmacy can sell that medicine on the Namma MedMate platform above the ceiling price. Price ceilings are typically applied to NLEM (National List of Essential Medicines) drugs, government price-controlled medicines, or in response to market price gouging. Pharmacies selling above the ceiling are flagged as violations and can be notified in bulk. The order placement flow enforces the ceiling at checkout. All ceiling changes are audit-logged.

---

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| `admin_super` | Full | Set, remove price ceilings; view violations; notify pharmacies |
| `admin_compliance` | Read + Notify | View ceilings and violations; can notify violating pharmacies |
| `admin_operations` | Read | View ceilings and violations |
| `pharmacy_owner` | Implicit (receive notification) | Notified when ceiling is set or violation detected |
| `customer` | Implicit (order enforcement) | Order placement enforces ceiling; pharmacy prices above ceiling are rejected |

---

## Business Rules

1. **Ceiling price must be ? medicine MRP**: A price ceiling must not exceed the medicine's current `mrp`. Attempting to set `ceiling_price > mrp` returns `CEILING_ABOVE_MRP`. Ceiling prices are typically set at or below MRP for essential medicines.
2. **Ceiling only affects the online selling price on the platform**: The price ceiling applies to `PharmacyCatalogueMapping.pharmacy_price` for online store sales. It does NOT affect in-store POS sales via the pharmacy dashboard (billing module). POS is exempt from ceiling enforcement.
3. **Order placement enforces ceiling at checkout**: The order creation endpoint (EPIC-008) validates that `pharmacy_price ? mrp_ceiling` for each medicine with an active ceiling. If violated, the order is rejected with `PRICE_CEILING_VIOLATED` listing the medicine name and ceiling price. The customer sees the ceiling price, not the pharmacy's higher price.
4. **Pharmacies exceeding ceiling are flagged but not auto-suspended**: Price ceiling violations are logged in the `PriceCeilingViolation` table and surfaced in the admin violations list. Pharmacies are not automatically suspended for violations; admin takes manual action (notify, then suspend if repeated).
5. **Ceiling takes effect immediately**: After POST `/price-ceiling`, the `MedicineMaster.mrp_ceiling` is updated immediately. Any pharmacy with `pharmacy_price > ceiling_price` for this medicine becomes a violation immediately. Violation records are computed in real time on each pricing check and also by a nightly batch job.
6. **Removing a ceiling removes all associated violations**: When DELETE `/price-ceiling` is called, the `MedicineMaster.mrp_ceiling` is set to `null`. All open (unresolved) `PriceCeilingViolation` records for this medicine are marked `RESOLVED` by the system.
7. **Ceiling changes are audit-logged**: Every set and remove action creates an `AuditLog` entry with `action`, `medicine_id`, `old_ceiling`, `new_ceiling`, `effective_from`, `reason`, and `actor_id`.
8. **Bulk notification to violating pharmacies**: POST `/price-violations/notify` sends a warning to all pharmacies currently violating any ceiling (or a specific medicine's ceiling). Rate limiting applies: max 1 batch notification per medicine per 4 hours.

---

## API Endpoints

### 1. List Medicines with Active Price Ceilings

```
GET /api/v1/admin/catalogue/price-ceilings
```

**Authentication:** Bearer JWT - `admin_super`, `admin_compliance`, `admin_operations`
**Rate Limit:** 60 req/min

**Query Parameters:**
| Param | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `category_id` | UUID | No | - | Filter by medicine category |
| `has_violations` | boolean | No | - | true = only medicines with violating pharmacies |
| `page` | integer | No | 1 | Page number |
| `limit` | integer | No | 20 | Records per page, max 100 |

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "price_ceilings": [
      {
        "medicine_id": "uuid-v4",
        "medicine_name": "Amoxicillin 500mg Capsule",
        "category": "Antibiotics",
        "schedule": "H",
        "current_mrp": 85.00,
        "ceiling_price": 72.00,
        "pharmacies_above_ceiling": 3,
        "effective_from": "2026-07-01",
        "set_by": {
          "admin_id": "uuid-v4",
          "name": "Kavya Reddy",
          "role": "admin_super"
        },
        "set_at": "2026-07-01T10:00:00Z",
        "reason": "NLEM price ceiling per NPPA notification NPM/2026/01"
      }
    ]
  },
  "meta": {
    "page": 1,
    "limit": 20,
    "total": 14
  }
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 403 | `FORBIDDEN` | Caller not an admin role |

---

### 2. Set Price Ceiling for a Medicine

```
POST /api/v1/admin/catalogue/:id/price-ceiling
```

**Authentication:** Bearer JWT - `admin_super`
**Rate Limit:** 20 req/min

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| `id` | UUID | Medicine master ID |

**Request Body (application/json):**
```json
{
  "ceiling_price": "number - required, positive decimal, must be ? medicine MRP",
  "effective_from": "string - optional, date YYYY-MM-DD; defaults to today if omitted",
  "reason": "string - required, max 500 chars, regulatory or policy justification"
}
```

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "medicine_id": "uuid-v4",
    "medicine_name": "Amoxicillin 500mg Capsule",
    "previous_ceiling": null,
    "new_ceiling_price": 72.00,
    "mrp": 85.00,
    "effective_from": "2026-07-01",
    "reason": "NLEM price ceiling per NPPA notification NPM/2026/01",
    "pharmacies_above_ceiling": 3,
    "set_at": "2026-07-24T00:00:00Z"
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `CEILING_ABOVE_MRP` | `ceiling_price` > medicine's `mrp` |
| 400 | `CEILING_PRICE_MUST_BE_POSITIVE` | `ceiling_price` ? 0 |
| 400 | `REASON_REQUIRED` | `reason` is empty |
| 403 | `FORBIDDEN` | Caller not admin_super |
| 404 | `MEDICINE_NOT_FOUND` | Medicine ID not found |
| 409 | `CEILING_ALREADY_SET` | Medicine already has an active ceiling; update it by setting a new one (overwrites) |

---

### 3. Remove Price Ceiling

```
DELETE /api/v1/admin/catalogue/:id/price-ceiling
```

**Authentication:** Bearer JWT - `admin_super`
**Rate Limit:** 20 req/min

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| `id` | UUID | Medicine master ID |

**Request Body (application/json):**
```json
{
  "reason": "string - required, max 500 chars, reason for removing ceiling"
}
```

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "medicine_id": "uuid-v4",
    "medicine_name": "Amoxicillin 500mg Capsule",
    "ceiling_removed": true,
    "violations_resolved": 3,
    "removed_at": "2026-07-24T00:00:00Z"
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `REASON_REQUIRED` | `reason` is empty |
| 403 | `FORBIDDEN` | Caller not admin_super |
| 404 | `MEDICINE_NOT_FOUND` | Medicine not found |
| 409 | `NO_CEILING_SET` | Medicine does not have an active price ceiling |

---

### 4. List Price Violations

```
GET /api/v1/admin/catalogue/price-violations
```

**Authentication:** Bearer JWT - `admin_super`, `admin_compliance`, `admin_operations`
**Rate Limit:** 60 req/min

**Query Parameters:**
| Param | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `medicine_id` | UUID | No | - | Filter violations for a specific medicine |
| `zone_id` | UUID | No | - | Filter violations by pharmacy zone |
| `page` | integer | No | 1 | Page number |
| `limit` | integer | No | 20 | Records per page, max 100 |

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "violations": [
      {
        "violation_id": "uuid-v4",
        "medicine_id": "uuid-v4",
        "medicine_name": "Amoxicillin 500mg Capsule",
        "ceiling_price": 72.00,
        "pharmacy_id": "uuid-v4",
        "pharmacy_name": "City Medicals",
        "pharmacy_price": 80.00,
        "overage_amount": 8.00,
        "overage_pct": 11.1,
        "zone": "Indiranagar Zone",
        "detected_at": "2026-07-24T00:00:00Z",
        "last_notified_at": null,
        "status": "OPEN"
      }
    ]
  },
  "meta": {
    "page": 1,
    "limit": 20,
    "total": 3
  }
}
```

---

### 5. Notify All Violating Pharmacies

```
POST /api/v1/admin/catalogue/price-violations/notify
```

**Authentication:** Bearer JWT - `admin_super`, `admin_compliance`
**Rate Limit:** 5 req/min

**Request Body (application/json):**
```json
{
  "medicine_id": "string (UUID) - optional; if provided, only notify violations for this medicine; if omitted, notify all open violations",
  "message": "string - optional, max 500 chars; appended to the standard violation notice template"
}
```

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "pharmacies_notified": 3,
    "violations_covered": 3,
    "channels": ["WHATSAPP", "IN_APP"],
    "notified_at": "2026-07-24T00:00:00Z",
    "next_batch_allowed_at": "2026-07-24T04:00:00Z"
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 403 | `FORBIDDEN` | Caller not admin_super or admin_compliance |
| 429 | `NOTIFICATION_RATE_LIMITED` | Batch notification for this medicine sent within last 4 hours |

---

## Data Models

### PriceCeilingViolation

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID v4 | PK | Violation record ID |
| `medicine_id` | UUID | FK ? MedicineMaster.id, not null | Medicine with ceiling |
| `pharmacy_id` | UUID | FK ? Pharmacy.id, not null | Violating pharmacy |
| `ceiling_price` | DECIMAL(10,2) | Not null | Ceiling price at time of detection |
| `pharmacy_price` | DECIMAL(10,2) | Not null | Pharmacy's price at time of detection |
| `overage_amount` | DECIMAL(10,2) | Not null | pharmacy_price - ceiling_price |
| `status` | ENUM | Not null, default OPEN | OPEN \| NOTIFIED \| RESOLVED |
| `detected_at` | TIMESTAMPTZ | Not null | When violation was detected |
| `last_notified_at` | TIMESTAMPTZ | Nullable | Last time pharmacy was notified about this violation |
| `resolved_at` | TIMESTAMPTZ | Nullable | When pharmacy corrected price or ceiling was removed |

---

## Acceptance Criteria

- [ ] **Given** POST `/api/v1/admin/catalogue/:id/price-ceiling` with `ceiling_price` greater than the medicine's `mrp`, **then** HTTP 400 `CEILING_ABOVE_MRP` is returned and no ceiling is set.
- [ ] **Given** a valid price ceiling is set with `ceiling_price=72` on a medicine with `mrp=85`, **then** `MedicineMaster.mrp_ceiling=72.00` is updated immediately and pharmacies with `pharmacy_price > 72` for this medicine appear in the violations list.
- [ ] **Given** a customer tries to place an order for a medicine where the pharmacy's price (80.00) exceeds the ceiling (72.00), **then** the order creation endpoint returns `PRICE_CEILING_VIOLATED` with the medicine name and ceiling price.
- [ ] **Given** DELETE `/api/v1/admin/catalogue/:id/price-ceiling` with a valid reason, **then** `MedicineMaster.mrp_ceiling=null`, all `OPEN` `PriceCeilingViolation` records for this medicine are updated to `RESOLVED`, and an `AuditLog` entry is written.
- [ ] **Given** GET `/api/v1/admin/catalogue/price-violations?medicine_id=:id`, **then** only violations for that medicine are returned with `overage_amount`, `overage_pct`, pharmacy name, zone, and detection time.
- [ ] **Given** POST `/api/v1/admin/catalogue/price-violations/notify` with `medicine_id`, **then** all pharmacies with open violations for that medicine receive a WhatsApp + in-app notification, `last_notified_at` is updated, and `next_batch_allowed_at` is set to 4 hours from now.
- [ ] **Given** a second batch notification is attempted for the same medicine within 4 hours, **then** HTTP 429 `NOTIFICATION_RATE_LIMITED` is returned.
- [ ] **Given** GET `/api/v1/admin/catalogue/price-ceilings?has_violations=true`, **then** only medicines with at least one `OPEN` violation are returned.

---

## Dependencies

- STORY-005-001 - Medicine Master CRUD (`mrp_ceiling` field on `MedicineMaster`)
- STORY-005-005 - Pharmacy Catalogue Mapping (`pharmacy_price` is checked against ceiling)
- EPIC-008 - Orders (ceiling enforcement at order checkout)
- EPIC-002 - Notifications (violation warning messages via WhatsApp and in-app)
- Infrastructure: Nightly batch job - detect new violations by comparing all pharmacy prices to active ceilings

---

## Notes

- The nightly violation detection job: `SELECT pcm.pharmacy_id, pcm.medicine_id, pcm.pharmacy_price, mm.mrp_ceiling FROM PharmacyCatalogueMapping pcm JOIN MedicineMaster mm ON pcm.master_medicine_id = mm.id WHERE mm.mrp_ceiling IS NOT NULL AND pcm.pharmacy_price > mm.mrp_ceiling AND pcm.is_visible = true`. For each result, UPSERT a `PriceCeilingViolation` record.
- Violation `status` transitions: OPEN ? NOTIFIED (after notification sent) ? RESOLVED (after pharmacy corrects price or ceiling is removed).
- Price ceiling source for NLEM medicines: The NPPA (National Pharmaceutical Pricing Authority) publishes ceiling prices periodically. Consider a future enhancement to auto-sync NPPA price lists to set ceilings automatically.
- Pharmacy-visible price ceiling notification WhatsApp template: `PHARMACY_PRICE_CEILING_VIOLATION`. Variables: `{medicine_name}`, `{ceiling_price}`, `{your_current_price}`.
- For the ceiling enforcement in the order API (EPIC-008): if `pharmacy_price > mrp_ceiling`, do not allow the order. Show customer: "This pharmacy's price for {medicine_name} (Rs {pharmacy_price}) exceeds the platform ceiling (Rs {ceiling_price}). Please choose another pharmacy."
