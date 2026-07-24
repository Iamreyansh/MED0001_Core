# STORY-002: Banner CMS Management

| Field | Value |
|---|---|
| Story ID | EPIC-013-STORY-002 |
| Epic | EPIC-013 Marketing and Growth |
| Title | Banner CMS Management |
| Priority | P1 |
| Status | Planned |
| Created | 2026-07-24 |
| Last Updated | 2026-07-24 |

---

## Overview

Banner CMS Management gives the Admin HQ marketing team full control over the visual promotional content displayed inside the Namma MedMate customer app. Banners can be placed across multiple placements (home top carousel, mid-page interstitial, category headers, offers section) and linked to categories, pharmacies, coupons, external URLs, or teleconsult. Admins schedule banners with future effective dates, control priority order within a placement, and track performance via real-time impression/click counters and computed CTR. The customer-facing API returns only live, non-expired banners for a given placement in priority order, making it the single source of truth for storefront content.

---

## User Roles

| Role | Capability |
|---|---|
| `admin_super` | Full CRUD; reorder; toggle; delete |
| `admin_operations` | Create, edit, toggle banners; reorder |
| `admin_finance` | Read-only CTR and analytics |
| `customer` | Read active banners for placement; trigger impression/click events |

---

## Business Rules

1. **Priority ordering** - within a placement, banners are sorted ascending by `priority` integer (lower = higher position). Multiple banners on the same placement are rendered as a carousel in this order.
2. **CDN requirement** - `image_url` must point to a pre-uploaded CDN asset; the banner creation endpoint validates the URL is reachable, is < 2 MB, and is JPG or PNG format. Raw file upload is out of scope here.
3. **Auto-deactivation** - a scheduled job runs every 15 minutes to set `is_live = false` on banners whose `valid_until` has passed.
4. **CTR computation** - `ctr_pct = (clicks / impressions) - 100`; computed on read, not stored. Analytics reset per campaign - if a banner is reused in a new campaign, impressions and clicks may be zeroed via admin action.
5. **Audit logging** - every create, update, toggle, reorder, and delete action is recorded in the audit log with actor, timestamp, and diff.
6. **Scheduling** - banners with `valid_from` in the future are stored with `is_live = true` but the customer-facing endpoint only returns banners where `valid_from ? now ? valid_until` AND `is_live = true`.
7. **Priority conflict resolution** - if two banners share the same `priority` within a placement, the system sub-sorts by `created_at` ascending (older first).
8. **Impression logging** - called by the frontend on every render; throttled server-side to one impression per `(banner_id, customer_id, session_id)` per 30-minute window to prevent inflation.

---

## API Endpoints

### 1. List Banners (Admin)

```
GET /api/v1/admin/banners
Authorization: Bearer JWT (admin_super | admin_operations | admin_finance)
```

**Query Parameters**

| Parameter | Type | Description |
|---|---|---|
| `placement` | string | Filter by placement |
| `is_live` | boolean | Filter by live status |
| `page` | integer | Default 1 |
| `limit` | integer | Default 20 |

**Response 200**
```json
{
  "success": true,
  "data": {
    "banners": [
      {
        "id": "ban_uuid_001",
        "placement": "HOME_TOP",
        "headline": "Monsoon Sale - Up to 25% Off",
        "image_url": "https://cdn.nammamedmate.com/banners/monsoon-2026.jpg",
        "link_action": { "type": "COUPON", "value": "NAMMA25" },
        "impressions": 128400,
        "clicks": 6420,
        "ctr_pct": 5.0,
        "priority": 1,
        "is_live": true,
        "valid_from": "2026-07-01T00:00:00Z",
        "valid_until": "2026-07-31T23:59:59Z"
      }
    ]
  },
  "meta": { "page": 1, "limit": 20, "total": 8 }
}
```

---

### 2. Create Banner (Admin)

```
POST /api/v1/admin/banners
Authorization: Bearer JWT (admin_super | admin_operations)
Content-Type: application/json
```

**Request Body**
```json
{
  "headline": "Monsoon Sale - Up to 25% Off",
  "sub_text": "Use code NAMMA25 at checkout",
  "image_url": "https://cdn.nammamedmate.com/banners/monsoon-2026.jpg",
  "placement": "HOME_TOP",
  "link_type": "COUPON",
  "link_value": "NAMMA25",
  "theme_color": "#1A73E8",
  "is_live": true,
  "valid_from": "2026-07-01T00:00:00Z",
  "valid_until": "2026-07-31T23:59:59Z",
  "priority": 1
}
```

**Response 201**
```json
{
  "success": true,
  "data": {
    "id": "ban_uuid_001",
    "headline": "Monsoon Sale - Up to 25% Off",
    "placement": "HOME_TOP",
    "status": "LIVE",
    "created_at": "2026-07-24T07:00:00Z"
  }
}
```

**Error Responses**

| HTTP | Error Code | Description |
|---|---|---|
| 422 | `INVALID_IMAGE_URL` | Image URL unreachable or not JPG/PNG |
| 422 | `IMAGE_TOO_LARGE` | Image exceeds 2 MB |
| 422 | `INVALID_DATE_RANGE` | `valid_from` after `valid_until` |
| 422 | `INVALID_PLACEMENT` | Placement not in allowed enum |

---

### 3. Update Banner (Admin)

```
PATCH /api/v1/admin/banners/:id
Authorization: Bearer JWT (admin_super | admin_operations)
Content-Type: application/json
```

**Request Body** (any updatable fields)
```json
{
  "headline": "Monsoon Mega Sale",
  "valid_until": "2026-08-15T23:59:59Z",
  "priority": 2
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "id": "ban_uuid_001",
    "updated_at": "2026-07-24T09:00:00Z"
  }
}
```

---

### 4. Toggle Banner Live/Offline (Admin)

```
PATCH /api/v1/admin/banners/:id/toggle
Authorization: Bearer JWT (admin_super | admin_operations)
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "id": "ban_uuid_001",
    "is_live": false,
    "toggled_at": "2026-07-24T10:00:00Z"
  }
}
```

---

### 5. Delete Banner (Admin)

```
DELETE /api/v1/admin/banners/:id
Authorization: Bearer JWT (admin_super)
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "id": "ban_uuid_001",
    "deleted": true
  }
}
```

**Error Responses**

| HTTP | Error Code | Description |
|---|---|---|
| 404 | `BANNER_NOT_FOUND` | Banner ID does not exist |

---

### 6. Bulk Reorder Banners (Admin)

```
PATCH /api/v1/admin/banners/reorder
Authorization: Bearer JWT (admin_super | admin_operations)
Content-Type: application/json
```

**Request Body**
```json
{
  "items": [
    { "id": "ban_uuid_001", "priority": 1 },
    { "id": "ban_uuid_002", "priority": 2 },
    { "id": "ban_uuid_003", "priority": 3 }
  ]
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "updated_count": 3,
    "reordered_at": "2026-07-24T10:05:00Z"
  }
}
```

**Error Responses**

| HTTP | Error Code | Description |
|---|---|---|
| 422 | `BANNER_NOT_FOUND` | One or more IDs not found |
| 422 | `MIXED_PLACEMENTS` | Items span multiple placements (not allowed) |

---

### 7. Get Active Banners (Customer-Facing)

```
GET /api/v1/banners
Authorization: Bearer JWT (customer)
```

**Query Parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `placement` | string | Yes | `HOME_TOP`, `HOME_MID`, `CATEGORY`, `OFFERS` |
| `lat` | decimal | No | Customer latitude for geo-filtering (future) |
| `lng` | decimal | No | Customer longitude for geo-filtering (future) |

**Response 200**
```json
{
  "success": true,
  "data": {
    "banners": [
      {
        "id": "ban_uuid_001",
        "headline": "Monsoon Sale - Up to 25% Off",
        "sub_text": "Use code NAMMA25 at checkout",
        "image_url": "https://cdn.nammamedmate.com/banners/monsoon-2026.jpg",
        "link_type": "COUPON",
        "link_value": "NAMMA25",
        "theme_color": "#1A73E8",
        "priority": 1
      }
    ]
  }
}
```

---

### 8. Log Banner Impression (Customer-Facing)

```
POST /api/v1/banners/:id/impression
Authorization: Bearer JWT (customer)
```

**Response 200**
```json
{
  "success": true,
  "data": { "logged": true }
}
```

---

### 9. Log Banner Click (Customer-Facing)

```
POST /api/v1/banners/:id/click
Authorization: Bearer JWT (customer)
```

**Response 200**
```json
{
  "success": true,
  "data": { "logged": true }
}
```

---

## Data Model

### Banner

| Field | Type | Constraints | Description |
|---|---|---|---|
| `id` | UUID v4 | PK | Internal identifier |
| `headline` | VARCHAR(120) | NOT NULL | Primary banner text |
| `sub_text` | VARCHAR(200) | NULLABLE | Secondary line |
| `image_url` | TEXT | NOT NULL | CDN asset URL |
| `placement` | ENUM | NOT NULL | `HOME_TOP`, `HOME_MID`, `CATEGORY`, `OFFERS` |
| `link_type` | ENUM | NOT NULL | `CATEGORY`, `PHARMACY`, `COUPON`, `EXTERNAL_URL`, `TELECONSULT` |
| `link_value` | TEXT | NOT NULL | Category ID, coupon code, URL, etc. |
| `theme_color` | VARCHAR(7) | NULLABLE | Hex color for UI theming |
| `is_live` | BOOLEAN | DEFAULT true | Admin-controlled live flag |
| `valid_from` | TIMESTAMPTZ | NOT NULL | Effective start |
| `valid_until` | TIMESTAMPTZ | NOT NULL | Auto-deactivates after |
| `priority` | INTEGER | DEFAULT 100 | Sort order within placement |
| `impressions` | BIGINT | DEFAULT 0 | Total impressions logged |
| `clicks` | BIGINT | DEFAULT 0 | Total clicks logged |
| `created_by` | UUID | FK ? admin_users | Creator |
| `created_at` | TIMESTAMPTZ | DEFAULT NOW() | Creation timestamp |
| `updated_at` | TIMESTAMPTZ | DEFAULT NOW() | Last update |

---

## Acceptance Criteria

1. Admin creates a banner; it appears in the admin list and the customer-facing `GET /api/v1/banners?placement=HOME_TOP` within 5 seconds.
2. A banner with `valid_until` in the past is not returned by the customer-facing endpoint, even if `is_live = true`.
3. Reorder endpoint updates priorities atomically; subsequent list returns banners in new priority order.
4. Impression log is throttled: calling `POST /api/v1/banners/:id/impression` twice within 30 minutes from the same session increments `impressions` only once.
5. CTR = `clicks / impressions - 100` and matches the counts visible in the admin detail view.
6. Toggle endpoint flips `is_live`; toggling a live banner takes it offline immediately (next customer API call does not return it).
7. Creating a banner with an image URL that returns HTTP 404 returns `INVALID_IMAGE_URL`.
8. Bulk reorder of 3 banners within the same placement succeeds; reorder across different placements returns `MIXED_PLACEMENTS`.
9. Expired banners (past `valid_until`) are auto-deactivated by the scheduled job and do not appear in customer lists.
10. Audit log contains a record for every create, update, toggle, reorder, and delete action.

---

## Dependencies

| Dependency | Description |
|---|---|
| CDN / File Storage | Image URL hosting; pre-upload required |
| Coupon Module (STORY-001) | `link_value` for COUPON link type |
| Scheduled Job Runner | Auto-deactivation of expired banners |
| Notification Engine | Impression/click event streaming (future analytics) |

---

## Notes

- Geo-filtering (`lat`, `lng`) is reserved for a future release to show hyper-local banners (e.g. pincode-specific promotions).
- Analytics reset: Admin can zero `impressions` and `clicks` counters on a banner via a separate admin action (not modelled here; scoped to v2).
- `CATEGORY` placement is rendered at the top of the relevant category page; requires the category ID to be passed as `link_value`.
