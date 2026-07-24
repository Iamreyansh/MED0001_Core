# STORY-004-001: Pharmacy Directory

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-004-001 |
| **Epic** | EPIC-004 - Pharmacy Operations (Admin View) |
| **Priority** | P0 |
| **Complexity** | M |
| **Status** | Draft |

---

## Overview

This story delivers the admin pharmacy directory - the primary operational view for managing all pharmacies on the platform. It provides a paginated, searchable, and filterable list of pharmacies with key operational metrics per row (orders today, GMV today, fill rate, online status, rating), a summary chip bar with platform-wide KPIs, and a full pharmacy detail drawer for deep-dives. The directory supports CSV export for offline analysis. It is the entry point for most admin workflows: KYC review, performance investigation, commission changes, and suspension actions all begin here. This endpoint is shared with the KYC pending queue from STORY-003-004, distinguished by the `status` filter.

---

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| `admin_super` | Full | View all pharmacies, all filters, export, all actions |
| `admin_operations` | Full | View all pharmacies, all filters, export, operational actions |
| `admin_finance` | Read + Commission | View all pharmacies, commission and payout data |
| `admin_support` | Read | View directory and detail; no action buttons |
| `admin_compliance` | Read | View directory with compliance fields; no financial data |

---

## Business Rules

1. **Admin sees ALL pharmacies regardless of zone**: The admin directory has no zone-level access restriction. Every admin role sees pharmacies across all zones and states. Zone filtering is available but not enforced.
2. **Search is fuzzy across name, owner, phone, and pharmacy code**: The `search` parameter is matched against `business_name`, `owner_name`, `phone`, and `code` fields using full-text search with trigram similarity. Minimum query length is 2 characters.
3. **Export CSV is bounded at 10,000 rows**: The export endpoint applies the same filters as the list endpoint but streams all matching rows (up to 10,000) as a CSV file. If total matching rows exceed 10,000, the export is truncated and a header comment indicates truncation.
4. **Default pagination is 50 per page**: Default `limit=50`, max `limit=200`. Pages beyond the last available page return an empty array (not an error).
5. **Operational metrics are cached daily**: `orders_today`, `gmv_today`, `fill_rate`, and `rating` are computed by a nightly aggregation job and cached in Redis. Real-time accuracy is not guaranteed; data reflects the state as of the last cache refresh (typically < 1 hour old during business hours).
6. **`urgency` field in KYC queue view**: When filtering by `status=PENDING_KYC` or `status=KYC_SUBMITTED`, each row includes an `urgency` field: `HIGH` if document_age > 48 hours since submission, `MEDIUM` if 24-48 hours, `LOW` if < 24 hours.
7. **Commission data is shown only to `admin_finance` and `admin_super`**: The `commission_pct` and `net_payout` fields are omitted from rows returned to `admin_support` and `admin_compliance` roles.
8. **Summary chips are refreshed every 5 minutes**: The `/summary` endpoint data is cached with a 5-minute TTL. Stale data is returned during cache refresh with a `data_as_of` timestamp.

---

## API Endpoints

### 1. List Pharmacy Directory

```
GET /api/v1/admin/pharmacies
```

**Authentication:** Bearer JWT - `admin_super`, `admin_operations`, `admin_finance`, `admin_support`, `admin_compliance`
**Rate Limit:** 60 req/min per admin

**Query Parameters:**
| Param | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `status` | string | No | ALL | ACTIVE \| PENDING_KYC \| KYC_SUBMITTED \| SUSPENDED \| REJECTED \| ALL |
| `zone_id` | UUID | No | - | Filter by zone |
| `plan` | string | No | - | FREE \| STARTER \| GROWTH \| PRO |
| `is_online` | boolean | No | - | true = online only, false = offline only |
| `search` | string | No | - | Fuzzy search: name, owner, phone, code (min 2 chars) |
| `sort` | string | No | `created_at` | created_at \| gmv_today \| orders_today \| rating \| fill_rate \| submitted_at |
| `order` | string | No | `desc` | asc \| desc |
| `page` | integer | No | 1 | Page number (1-indexed) |
| `limit` | integer | No | 50 | Rows per page, max 200 |

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "pharmacies": [
      {
        "pharmacy_id": "uuid-v4",
        "code": "PHM-0042",
        "business_name": "Sharma Medical Store",
        "owner_name": "Rajesh Sharma",
        "phone": "+919876543210",
        "zone": {
          "zone_id": "uuid-v4",
          "zone_name": "Koramangala Zone"
        },
        "status": "ACTIVE",
        "plan": "GROWTH",
        "is_online": true,
        "rating": 4.3,
        "review_count": 128,
        "orders_today": 34,
        "gmv_today": 18750.00,
        "fill_rate_pct": 91.2,
        "commission_pct": 8.00,
        "net_payout": 1725.00,
        "metrics_as_of": "2026-07-24T00:00:00Z",
        "created_at": "2026-06-01T00:00:00Z"
      }
    ]
  },
  "meta": {
    "page": 1,
    "limit": 50,
    "total": 342,
    "total_pages": 7
  }
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `INVALID_SORT_FIELD` | `sort` value not in allowed list |
| 403 | `FORBIDDEN` | Caller is not an admin role |

---

### 2. Pharmacy Directory Summary Chips

```
GET /api/v1/admin/pharmacies/summary
```

**Authentication:** Bearer JWT - `admin_super`, `admin_operations`, `admin_finance`, `admin_support`, `admin_compliance`
**Rate Limit:** 60 req/min

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "total_active": 342,
    "pending_kyc": 18,
    "kyc_submitted": 11,
    "suspended": 7,
    "rejected": 23,
    "currently_online": 289,
    "gmv_today": 1482500.00,
    "commission_today": 118600.00,
    "orders_today": 3820,
    "payout_due": 94250.00,
    "data_as_of": "2026-07-24T00:05:00Z",
    "cache_ttl_seconds": 300
  },
  "meta": {}
}
```

---

### 3. Get Full Pharmacy Detail (Admin Drawer)

```
GET /api/v1/admin/pharmacies/:id
```

**Authentication:** Bearer JWT - `admin_super`, `admin_operations`, `admin_finance`, `admin_support`, `admin_compliance`
**Rate Limit:** 60 req/min

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| `id` | UUID | Pharmacy ID |

**Success Response - 200 OK:**
```json
{
  "success": true,
  "data": {
    "pharmacy_id": "uuid-v4",
    "code": "PHM-0042",
    "business_name": "Sharma Medical Store",
    "owner_name": "Rajesh Sharma",
    "phone": "+919876543210",
    "email": "rajesh@sharma.com",
    "business_type": "PHARMACY",
    "address": {
      "flat": "12",
      "area": "Koramangala 4th Block",
      "city": "Bengaluru",
      "state": "Karnataka",
      "pincode": "560034",
      "latitude": 12.9352,
      "longitude": 77.6245
    },
    "gstin": "29AABCS1429B1ZB",
    "drug_licence_number": "KA/DL/2024/12345",
    "fssai_number": "11223344556677",
    "pan_number": "AABCS1429B",
    "status": "ACTIVE",
    "plan": "GROWTH",
    "plan_expires_at": "2026-08-01T00:00:00Z",
    "commission_pct": 8.00,
    "zone": {
      "zone_id": "uuid-v4",
      "zone_name": "Koramangala Zone"
    },
    "is_online": true,
    "kyc_status": "ACTIVE",
    "performance": {
      "fill_rate_pct": 91.2,
      "on_time_prep_pct": 88.5,
      "cancel_rate_pct": 3.1,
      "avg_rating": 4.3,
      "review_count": 128,
      "orders_30d": 842,
      "gmv_30d": 485000.00
    },
    "commission_ledger": {
      "gmv_current_period": 185000.00,
      "commission_earned": 14800.00,
      "tcs_deducted": 1850.00,
      "net_payable": 12950.00,
      "last_settlement_date": "2026-07-17",
      "next_settlement_date": "2026-07-24"
    },
    "catalogue_stats": {
      "mapped_skus": 234,
      "in_stock_skus": 198,
      "out_of_stock_skus": 36
    },
    "recent_orders": [
      {
        "order_id": "uuid-v4",
        "order_number": "ORD-20260724-0042",
        "status": "DELIVERED",
        "amount": 450.00,
        "created_at": "2026-07-24T00:00:00Z"
      }
    ],
    "created_at": "2026-06-01T00:00:00Z",
    "updated_at": "2026-07-24T00:00:00Z"
  },
  "meta": {}
}
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 403 | `FORBIDDEN` | Caller is not an admin role |
| 404 | `PHARMACY_NOT_FOUND` | Pharmacy ID not found |

---

### 4. Export Pharmacy Directory as CSV

```
GET /api/v1/admin/pharmacies/export
```

**Authentication:** Bearer JWT - `admin_super`, `admin_operations`, `admin_finance`
**Rate Limit:** 5 req/min per admin (export is expensive)

**Query Parameters:** (same as list endpoint filters)
| Param | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `status` | string | No | ALL | Status filter |
| `zone_id` | UUID | No | - | Zone filter |
| `plan` | string | No | - | Plan filter |
| `search` | string | No | - | Search filter |

**Success Response - 200 OK:**
```
Content-Type: text/csv
Content-Disposition: attachment; filename="pharmacies-export-2026-07-24.csv"

# Namma MedMate Pharmacy Export | 2026-07-24 | Total rows: 342
code,business_name,owner_name,phone,email,zone,status,plan,is_online,rating,orders_today,gmv_today,fill_rate_pct,commission_pct,net_payout,created_at
PHM-0042,Sharma Medical Store,Rajesh Sharma,+919876543210,rajesh@sharma.com,Koramangala Zone,ACTIVE,GROWTH,true,4.3,34,18750.00,91.2,8.00,1725.00,2026-06-01
```

**Error Responses:**
| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 403 | `FORBIDDEN` | Caller not authorised for export |
| 429 | `RATE_LIMIT_EXCEEDED` | Export rate limit reached |

---

## Data Models

### PharmacyDirectoryRow (computed view / materialised cache)

| Field | Type | Description |
|-------|------|-------------|
| `pharmacy_id` | UUID | Pharmacy identifier |
| `code` | VARCHAR(12) | Human-readable code e.g. PHM-0042 |
| `business_name` | VARCHAR(120) | Business name |
| `owner_name` | VARCHAR(100) | Owner full name |
| `phone` | VARCHAR(15) | Contact phone |
| `zone_id` | UUID | Assigned zone ID |
| `zone_name` | VARCHAR(100) | Zone display name |
| `status` | ENUM | Current pharmacy status |
| `plan` | ENUM | Current plan |
| `is_online` | BOOLEAN | Online flag |
| `rating` | DECIMAL(3,2) | Avg customer rating (0.00-5.00) |
| `review_count` | INTEGER | Total reviews received |
| `orders_today` | INTEGER | Orders received today (calendar day) |
| `gmv_today` | DECIMAL(12,2) | Gross merchandise value today (INR) |
| `fill_rate_pct` | DECIMAL(5,2) | 30-day fill rate percentage |
| `commission_pct` | DECIMAL(5,2) | Current commission percentage |
| `net_payout` | DECIMAL(12,2) | Net payout due in current settlement period |
| `metrics_as_of` | TIMESTAMPTZ | Timestamp of last metric cache refresh |

---

## Acceptance Criteria

- [ ] **Given** GET `/api/v1/admin/pharmacies?status=ACTIVE&sort=gmv_today&order=desc`, **then** only ACTIVE pharmacies are returned, sorted by `gmv_today` descending, paginated with default `limit=50`.
- [ ] **Given** `search=Sharma` is passed, **then** pharmacies where `business_name`, `owner_name`, `phone`, or `code` fuzzy-matches "Sharma" are returned; minimum 2-char query is enforced.
- [ ] **Given** GET `/api/v1/admin/pharmacies/summary`, **then** all chip values (`total_active`, `pending_kyc`, `gmv_today`, `commission_today`, `payout_due`) are returned with a `data_as_of` timestamp reflecting last cache refresh (? 5 minutes old).
- [ ] **Given** GET `/api/v1/admin/pharmacies/:id` for a valid pharmacy, **then** the response includes `performance`, `commission_ledger`, `catalogue_stats`, and at least the 5 most recent orders.
- [ ] **Given** `commission_pct` and `net_payout` are requested by an `admin_support` role, **then** those fields are omitted from the response (not returned as null - simply absent from the JSON).
- [ ] **Given** GET `/api/v1/admin/pharmacies/export` with `status=SUSPENDED`, **then** a CSV file is streamed with all suspended pharmacies (up to 10,000 rows), including a header comment with the total row count.
- [ ] **Given** more than 200 is passed as `limit`, **then** the API clamps it to 200 and processes accordingly.
- [ ] **Given** an `admin_compliance` role calls the export endpoint, **then** HTTP 403 `FORBIDDEN` is returned.

---

## Dependencies

- STORY-003-001 - Pharmacy registration (source of pharmacy records)
- STORY-003-004 - KYC Status Management (status filter for KYC queue)
- STORY-004-002 - Performance metrics (used in detail view and sortable columns)
- STORY-004-003 - Commission & Payout (commission ledger in detail view)
- EPIC-008 - Orders (order data for `orders_today`, `gmv_today`, `recent_orders`)
- EPIC-009 - Zone Management (zone names and IDs)
- Infrastructure: Redis - performance metric cache, summary chip cache

---

## Notes

- The `code` field (PHM-0042) is auto-assigned at pharmacy creation as `PHM-{zero-padded sequential integer}`. It is unique, human-readable, and used in customer communications and support tickets.
- The directory list query should use a database view or materialised view (`pharmacy_directory_view`) that joins Pharmacy, Zone, and the daily metrics cache. Avoid N+1 queries.
- For the sort by `gmv_today` or `orders_today`, if metrics cache is stale or unavailable, return the list sorted by `created_at` with a `metrics_unavailable: true` flag in the meta.
- CSV export should stream the response using chunked transfer encoding; do not buffer the entire export in memory.
- Consider adding a `last_seen_online` timestamp to PharmacyDirectoryRow to help admin identify pharmacies that have been offline for extended periods.
