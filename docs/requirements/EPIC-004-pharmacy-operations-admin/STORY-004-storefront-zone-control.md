# STORY-004-004: Storefront & Zone Control

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-004-004 |
| **Epic** | EPIC-004 - Pharmacy Operations (Admin View) |
| **Priority** | P0 |
| **Complexity** | S |
| **Status** | Draft |

---

## Overview

This story covers admin operational controls over pharmacy storefronts and zone assignments. Admins can toggle a pharmacy's online/offline visibility on the customer app (overriding the pharmacy owner's own toggle), reassign pharmacies between delivery zones, view zone coverage stats, and temporarily pause a pharmacy's catalogue (hiding all items from the storefront without fully taking the pharmacy offline). These controls are essential for managing marketplace supply - responding to storms, local closures, zone reorganisation, or quality issues - without needing a full suspension. Changes take effect immediately and are reflected in customer app queries within 5 minutes through cache invalidation.

---

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| `admin_super` | Full | Toggle online/offline, zone reassign, catalogue pause |
| `admin_operations` | Full | Toggle online/offline, zone reassign, catalogue pause |
| `admin_support` | Read | View zone assignments and online status only |
| `pharmacy_owner` | Write (own) | Can toggle own online/offline status via pharmacy dashboard (separate endpoint) |

---

## Business Rules

1. **Toggling offline immediately hides pharmacy from customer app**: Setting `is_online=false` via the admin storefront toggle or the pharmacy owner's toggle takes effect within 5 seconds. The pharmacy is excluded from all customer search, nearby pharmacy, and order placement queries. Cache invalidation is triggered synchronously for the affected zone.
2. **New orders cannot be placed from an offline pharmacy**: The order creation endpoint (EPIC-008) checks `is_online=true` before accepting a new order. Offline pharmacies return `PHARMACY_OFFLINE` error to customers attempting to order.
3. **Existing orders continue when pharmacy goes offline**: Orders already in `PENDING`, `ACCEPTED`, or `PREPARING` status continue their lifecycle when a pharmacy goes offline. Only new order creation is blocked.
4. **Zone assignment change propagates within 5 minutes**: When a pharmacy is reassigned to a new zone, the change is applied to the `Pharmacy.zone_id` column immediately. Redis keys for the old zone and new zone pharmacies list are invalidated. Customer app queries reflect the new zone within 5 minutes (cache TTL).
5. **Catalogue pause hides all items but does not affect pharmacy's `is_online` flag**: A catalogue pause temporarily sets all `PharmacyCatalogueMapping.is_visible=false` for the pharmacy for the specified duration. After `duration_minutes`, items are automatically restored. The pharmacy remains technically "online" but has no available items. Customers see "Temporarily unavailable" instead of products.
6. **Admin toggle overrides pharmacy owner toggle**: The admin's `PATCH /admin/pharmacies/:id/storefront` endpoint sets an admin override flag (`admin_forced_offline`). When this flag is set, the pharmacy cannot come back online via their own dashboard toggle until admin explicitly removes the override.
7. **Reason is logged for admin toggles**: The `reason` field is optional for admin toggles but is stored in the AuditLog when provided. Pharmacy owner's own toggle does not require a reason.
8. **Zone list requires at least one pharmacy**: Zones with zero pharmacies are shown with a warning flag but are not automatically deleted. Zone deletion is managed separately in EPIC-009.

---

## API Endpoints

### 1. Admin Toggle Pharmacy Online/Offline

```
PATCH /api/v1/admin/pharmacies/:id/storefront
```

**Authentication:** Bearer JWT - `admin_super`, `admin_operations`
**Rate Limit:** 30 req/min

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| `id` | UUID | Pharmacy ID |

**Request Body (application/json):**
```json
{
  "is_online": "boolean - required; true to bring online, false to take offline",
  "reason": "string - optional, max 500 chars; stored in audit log"
}
```

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "pharmacy_id": "uuid-v4",
    "is_online": false,
    "admin_forced_offline": true,
    "reason": "Temporary closure due to local emergency",
    "changed_at": "2026-07-24T00:00:00Z",
    "cache_invalidated": true,
    "customer_app_reflects_change_in_seconds": 5
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 403 | `FORBIDDEN` | Caller not admin_super or admin_operations |
| 404 | `PHARMACY_NOT_FOUND` | Pharmacy ID not found |
| 409 | `PHARMACY_NOT_ACTIVE` | Pharmacy not in ACTIVE status; cannot toggle storefront |

---

### 2. Reassign Pharmacy to a Zone

```
PATCH /api/v1/admin/pharmacies/:id/zone
```

**Authentication:** Bearer JWT - `admin_super`, `admin_operations`
**Rate Limit:** 20 req/min

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| `id` | UUID | Pharmacy ID |

**Request Body (application/json):**
```json
{
  "zone_id": "string (UUID) - required, must be an active zone ID",
  "effective_from": "string - optional, datetime ISO 8601; defaults to immediate if omitted"
}
```

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "pharmacy_id": "uuid-v4",
    "previous_zone_id": "old-zone-uuid",
    "previous_zone_name": "Indiranagar Zone",
    "new_zone_id": "new-zone-uuid",
    "new_zone_name": "Koramangala Zone",
    "effective_from": "2026-07-24T00:00:00Z",
    "cache_invalidation_triggered": true,
    "customer_app_reflects_change_in_minutes": 5
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `INVALID_ZONE` | `zone_id` not found or not an active zone |
| 403 | `FORBIDDEN` | Caller not admin_super or admin_operations |
| 404 | `PHARMACY_NOT_FOUND` | Pharmacy ID not found |
| 409 | `ALREADY_IN_ZONE` | Pharmacy is already assigned to the specified zone |

---

### 3. List All Zones (Admin)

```
GET /api/v1/admin/zones
```

**Authentication:** Bearer JWT - `admin_super`, `admin_operations`, `admin_support`, `admin_compliance`
**Rate Limit:** 60 req/min

**Query Parameters:**
| Param | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `city` | string | No | - | Filter zones by city name |
| `is_active` | boolean | No | true | Filter by active status |

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "zones": [
      {
        "zone_id": "uuid-v4",
        "zone_name": "Koramangala Zone",
        "city": "Bengaluru",
        "state": "Karnataka",
        "is_active": true,
        "pharmacy_count": 12,
        "online_pharmacy_count": 9,
        "coverage_area_sqkm": 8.4,
        "has_low_pharmacy_warning": false,
        "created_at": "2026-01-01T00:00:00Z"
      },
      {
        "zone_id": "uuid-v4",
        "zone_name": "Whitefield Zone",
        "city": "Bengaluru",
        "state": "Karnataka",
        "is_active": true,
        "pharmacy_count": 2,
        "online_pharmacy_count": 1,
        "coverage_area_sqkm": 12.1,
        "has_low_pharmacy_warning": true,
        "created_at": "2026-03-15T00:00:00Z"
      }
    ]
  },
  "meta": {
    "total": 24
  }
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 403 | `FORBIDDEN` | Caller is not an admin role |

---

### 4. Temporarily Pause Pharmacy Catalogue

```
POST /api/v1/admin/pharmacies/:id/catalogue/pause
```

**Authentication:** Bearer JWT - `admin_super`, `admin_operations`
**Rate Limit:** 10 req/min per admin

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| `id` | UUID | Pharmacy ID |

**Request Body (application/json):**
```json
{
  "duration_minutes": "integer - required, 1-1440 (max 24 hours)",
  "reason": "string - required, max 500 chars, stored in audit log"
}
```

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "pharmacy_id": "uuid-v4",
    "catalogue_paused": true,
    "pause_reason": "Inventory audit in progress",
    "paused_at": "2026-07-24T00:00:00Z",
    "auto_resume_at": "2026-07-24T02:00:00Z",
    "items_hidden_count": 234,
    "is_online": true
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `INVALID_DURATION` | `duration_minutes` outside 1-1440 range |
| 400 | `REASON_REQUIRED` | `reason` is empty |
| 403 | `FORBIDDEN` | Caller not admin_super or admin_operations |
| 404 | `PHARMACY_NOT_FOUND` | Pharmacy ID not found |
| 409 | `CATALOGUE_ALREADY_PAUSED` | Catalogue is already in paused state |

---

### 5. Pharmacy Owner - Toggle Own Online/Offline

```
PATCH /api/v1/pharmacy/storefront
```

**Authentication:** Bearer JWT - `pharmacy_owner`
**Rate Limit:** 30 req/min

**Request Body (application/json):**
```json
{
  "is_online": "boolean - required"
}
```

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "pharmacy_id": "uuid-v4",
    "is_online": true,
    "admin_forced_offline": false,
    "changed_at": "2026-07-24T00:00:00Z"
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 403 | `ADMIN_OVERRIDE_ACTIVE` | Admin has forced the pharmacy offline; cannot override |
| 403 | `PHARMACY_NOT_ACTIVE` | Pharmacy not in ACTIVE status |

---

## Data Models

### Zone

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID v4 | PK | Unique zone identifier |
| `zone_name` | VARCHAR(100) | Not null, unique per city | Human-readable zone name |
| `city` | VARCHAR(100) | Not null | City name |
| `state` | VARCHAR(100) | Not null | State name |
| `boundary_polygon` | GEOMETRY(POLYGON, 4326) | Nullable | PostGIS polygon for geographic boundary |
| `coverage_area_sqkm` | DECIMAL(8,2) | Nullable | Computed area in sq km |
| `is_active` | BOOLEAN | Not null, default true | Whether zone is active |
| `created_at` | TIMESTAMPTZ | Not null, default now() | Creation timestamp |

### CataloguePause

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID v4 | PK | Pause record ID |
| `pharmacy_id` | UUID | FK ? Pharmacy.id, not null, unique where active | Pharmacy being paused |
| `reason` | TEXT | Not null | Admin-provided reason |
| `paused_at` | TIMESTAMPTZ | Not null | When pause started |
| `auto_resume_at` | TIMESTAMPTZ | Not null | When pause automatically ends |
| `resumed_at` | TIMESTAMPTZ | Nullable | Actual resume time (if manually ended or auto-ended) |
| `items_hidden_count` | INTEGER | Not null | Number of catalogue items hidden |
| `paused_by` | UUID | FK ? User.id, not null | Admin who initiated the pause |

---

## Acceptance Criteria

- [ ] **Given** PATCH `/api/v1/admin/pharmacies/:id/storefront` with `is_online=false`, **then** `Pharmacy.is_online=false` is set, `admin_forced_offline=true` is flagged, zone pharmacy list cache is invalidated, and a customer attempting to place an order at that pharmacy gets `PHARMACY_OFFLINE`.
- [ ] **Given** `admin_forced_offline=true` on a pharmacy, **when** the pharmacy_owner calls PATCH `/api/v1/pharmacy/storefront` with `is_online=true`, **then** HTTP 403 `ADMIN_OVERRIDE_ACTIVE` is returned.
- [ ] **Given** PATCH `/api/v1/admin/pharmacies/:id/zone` with a valid new `zone_id`, **then** `Pharmacy.zone_id` is updated, the old zone and new zone pharmacy list caches are invalidated, and the response confirms cache invalidation with `customer_app_reflects_change_in_minutes=5`.
- [ ] **Given** POST `/api/v1/admin/pharmacies/:id/catalogue/pause` with `duration_minutes=120` and a reason, **then** all `PharmacyCatalogueMapping` records for the pharmacy have `is_visible=false`, a `CataloguePause` record is created, `auto_resume_at` is set to 2 hours from now, and `items_hidden_count` reflects the count hidden.
- [ ] **Given** a catalogue pause expires (current time reaches `auto_resume_at`), **then** all hidden catalogue items are automatically restored to their previous visibility state.
- [ ] **Given** GET `/api/v1/admin/zones`, **then** all zones are returned with `pharmacy_count`, `online_pharmacy_count`, and `has_low_pharmacy_warning=true` for zones with `pharmacy_count < 3`.
- [ ] **Given** a zone reassignment is made, **then** an `AuditLog` entry is created with `action=ZONE_REASSIGNED`, `old_zone_id`, `new_zone_id`, and `actor_id`.
- [ ] **Given** `PATCH /storefront` is called on a pharmacy in `SUSPENDED` status, **then** HTTP 409 `PHARMACY_NOT_ACTIVE` is returned by both admin and pharmacy_owner endpoints.

---

## Dependencies

- STORY-003-001 - Pharmacy registration (Pharmacy record, zone_id field)
- STORY-004-001 - Pharmacy Directory (online status shown in directory)
- EPIC-005 / STORY-005 - Pharmacy Catalogue Mapping (items hidden on catalogue pause)
- EPIC-008 - Orders (order placement checks `is_online`)
- EPIC-009 - Zone Management (zone definitions and boundaries)
- Infrastructure: Redis - zone pharmacy list cache, online status cache

---

## Notes

- Cache invalidation on storefront toggle: invalidate `zone:{zone_id}:pharmacies` key in Redis. The customer app pharmacy list is rebuilt from database on next request and cached with a 5-minute TTL.
- `admin_forced_offline` flag ensures that operational staff can take a pharmacy offline for quality control and the pharmacy owner cannot circumvent this by toggling back online via their dashboard. The flag is cleared when admin explicitly sets `is_online=true`.
- Catalogue auto-resume is implemented as a scheduled job that polls `CataloguePause` records where `auto_resume_at <= NOW()` and `resumed_at IS NULL`, restoring visibility and setting `resumed_at`.
- `has_low_pharmacy_warning` threshold: zones with fewer than 3 active, online pharmacies are flagged. This threshold is configurable via platform config.
- Pharmacy owner's own toggle should be reflected in the pharmacy dashboard UI immediately (optimistic update) with the server confirming within 2 seconds.
