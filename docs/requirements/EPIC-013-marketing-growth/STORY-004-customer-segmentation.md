# STORY-004: Customer Segmentation

| Field | Value |
|---|---|
| Story ID | EPIC-013-STORY-004 |
| Epic | EPIC-013 Marketing and Growth |
| Title | Customer Segmentation |
| Priority | P1 |
| Status | Planned |
| Created | 2026-07-24 |
| Last Updated | 2026-07-24 |

---

## Overview

Customer Segmentation provides Admin HQ with the ability to define, compute, and manage named groups of customers for targeted marketing, coupon scoping, and analytics. The platform ships eight system-defined segments (NEW, REGULAR, LOYAL, VIP, DORMANT, RX_USERS, HIGH_VALUE_AREA, and a catch-all ALL) that are recomputed nightly via a background job. Admins can also create custom segments using a rule builder with criteria across order history, LTV, recency, geography, and loyalty tier. Custom segments are computed on demand or on a schedule, with a last-computed timestamp shown in the UI. Segments power the campaign audience selector, coupon scope, and referral analytics.

---

## User Roles

| Role | Capability |
|---|---|
| `admin_super` | Full CRUD on segments; delete any segment |
| `admin_operations` | Create, edit, recompute custom segments; view all segments |
| `admin_finance` | Read-only access to segment statistics |

---

## Business Rules

1. **System segments are immutable** - system segments (`segment_type = SYSTEM`) cannot be edited or deleted; only their computed membership is refreshed nightly.
2. **Nightly recomputation** - system segments recompute at 02:00 IST every day; `last_computed_at` is updated on each successful run.
3. **AND logic for custom criteria** - all criteria in a custom segment definition use AND logic (a customer must satisfy every criterion).
4. **Compute is async** - triggering `POST /api/v1/admin/segments/:id/compute` enqueues a background job; the endpoint returns immediately with `job_id`; completion updates `last_computed_at`.
5. **Custom segment scope** - custom segments are customer-scoped (not pharmacy-specific or geography-locked unless a geography criterion is included).
6. **Cannot delete system segments** - attempting to delete a segment with `segment_type = SYSTEM` returns HTTP 403 `CANNOT_DELETE_SYSTEM_SEGMENT`.
7. **Segment count freshness** - `customer_count` shown in the list is the count at `last_computed_at`; it may differ from real-time if a recompute hasn't run.
8. **Segment dependency** - a custom segment referenced by an active campaign or coupon cannot be deleted until those dependencies are removed.

---

## System Segment Definitions

| Segment Name | Type | Criteria |
|---|---|---|
| `NEW` | SYSTEM | `total_orders ? 1` AND `account_age_days < 7` |
| `REGULAR` | SYSTEM | `total_orders` between 2 and 9 |
| `LOYAL` | SYSTEM | `total_orders` between 10 and 29, OR 3+ orders in last 30 days |
| `VIP` | SYSTEM | `total_orders ? 30` OR `ltv_rs > 10000` |
| `DORMANT` | SYSTEM | No order in last 60+ days |
| `RX_USERS` | SYSTEM | Has ever placed an Rx (prescription) order |
| `HIGH_VALUE_AREA` | SYSTEM | Delivery address in configured high-value zones/pincodes |
| `ALL` | SYSTEM | All registered customers |

---

## Allowed Custom Segment Criteria Fields

| Field | Operators | Value Type | Description |
|---|---|---|---|
| `total_orders` | `=`, `>`, `<`, `>=`, `<=`, `between` | integer | Lifetime order count |
| `ltv_rs` | `>`, `<`, `>=`, `<=`, `between` | decimal | Lifetime value in Rs |
| `last_order_days_ago` | `>`, `<`, `>=`, `<=` | integer | Days since last order |
| `avg_order_value_rs` | `>`, `<`, `>=`, `<=` | decimal | Average order value |
| `city` | `in`, `not_in` | string[] | City names |
| `pincode` | `in`, `not_in` | string[] | Pincode list |
| `has_rx_orders` | `=` | boolean | Has placed Rx order |
| `loyalty_tier` | `in` | string[] | `NONE`, `SILVER`, `GOLD`, `PLATINUM` |

---

## API Endpoints

### 1. List Segments (Admin)

```
GET /api/v1/admin/segments
Authorization: Bearer JWT (admin_super | admin_operations | admin_finance)
```

**Query Parameters**

| Parameter | Type | Description |
|---|---|---|
| `segment_type` | string | `SYSTEM` or `CUSTOM` |
| `page` | integer | Default 1 |
| `limit` | integer | Default 20 |

**Response 200**
```json
{
  "success": true,
  "data": {
    "segments": [
      {
        "id": "seg_uuid_vip",
        "name": "VIP",
        "description": "30+ orders or LTV > Rs 10,000",
        "customer_count": 1840,
        "avg_aov_rs": 620,
        "total_ltv_rs": 18420000,
        "last_computed_at": "2026-07-24T02:15:00Z",
        "segment_type": "SYSTEM"
      },
      {
        "id": "seg_uuid_high_aov",
        "name": "High AOV Bangalore",
        "description": "Bangalore customers with avg order > Rs 800",
        "customer_count": 540,
        "avg_aov_rs": 1050,
        "total_ltv_rs": 4200000,
        "last_computed_at": "2026-07-23T14:30:00Z",
        "segment_type": "CUSTOM"
      }
    ]
  },
  "meta": { "page": 1, "limit": 20, "total": 12 }
}
```

---

### 2. Create Custom Segment (Admin)

```
POST /api/v1/admin/segments
Authorization: Bearer JWT (admin_super | admin_operations)
Content-Type: application/json
```

**Request Body**
```json
{
  "name": "High AOV Bangalore",
  "description": "Bangalore customers with avg order above Rs 800",
  "criteria": [
    { "field": "city", "operator": "in", "value": ["Bangalore"] },
    { "field": "avg_order_value_rs", "operator": ">", "value": 800 },
    { "field": "total_orders", "operator": ">=", "value": 3 }
  ]
}
```

**Response 201**
```json
{
  "success": true,
  "data": {
    "id": "seg_uuid_high_aov",
    "name": "High AOV Bangalore",
    "segment_type": "CUSTOM",
    "status": "PENDING_COMPUTE",
    "created_at": "2026-07-24T10:00:00Z"
  }
}
```

**Error Responses**

| HTTP | Error Code | Description |
|---|---|---|
| 422 | `INVALID_CRITERIA_FIELD` | A criterion references an unsupported field |
| 422 | `INVALID_OPERATOR` | Operator not valid for the field type |
| 422 | `EMPTY_CRITERIA` | No criteria provided |
| 409 | `SEGMENT_NAME_EXISTS` | Name already in use |

---

### 3. Get Segment Detail (Admin)

```
GET /api/v1/admin/segments/:id
Authorization: Bearer JWT (admin_super | admin_operations | admin_finance)
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "id": "seg_uuid_vip",
    "name": "VIP",
    "segment_type": "SYSTEM",
    "customer_count": 1840,
    "criteria": [
      { "field": "total_orders", "operator": ">=", "value": 30 }
    ],
    "avg_aov_rs": 620,
    "avg_ltv_rs": 10011,
    "growth_chart": [
      { "date": "2026-07-01", "count": 1740 },
      { "date": "2026-07-08", "count": 1780 },
      { "date": "2026-07-15", "count": 1810 },
      { "date": "2026-07-22", "count": 1840 }
    ],
    "recommended_actions": [
      "Target with exclusive PLATINUM loyalty invite",
      "Send early-access campaign for new product launches"
    ],
    "last_computed_at": "2026-07-24T02:15:00Z"
  }
}
```

---

### 4. Recompute Segment (Admin)

```
POST /api/v1/admin/segments/:id/compute
Authorization: Bearer JWT (admin_super | admin_operations)
```

**Response 202**
```json
{
  "success": true,
  "data": {
    "id": "seg_uuid_high_aov",
    "job_id": "job_uuid_001",
    "status": "ENQUEUED",
    "message": "Segment computation enqueued. Results available in 2-5 minutes."
  }
}
```

---

### 5. Delete Custom Segment (Admin)

```
DELETE /api/v1/admin/segments/:id
Authorization: Bearer JWT (admin_super)
```

**Response 200**
```json
{
  "success": true,
  "data": { "id": "seg_uuid_high_aov", "deleted": true }
}
```

**Error Responses**

| HTTP | Error Code | Description |
|---|---|---|
| 403 | `CANNOT_DELETE_SYSTEM_SEGMENT` | Segment type is SYSTEM |
| 409 | `SEGMENT_IN_USE` | Referenced by active campaign or coupon |
| 404 | `SEGMENT_NOT_FOUND` | ID does not exist |

---

### 6. List Customers in Segment (Admin)

```
GET /api/v1/admin/segments/:id/customers
Authorization: Bearer JWT (admin_super | admin_operations)
```

**Query Parameters**

| Parameter | Type | Description |
|---|---|---|
| `page` | integer | Default 1 |
| `limit` | integer | Default 20 |
| `sort` | string | e.g. `ltv_rs`, `total_orders` |
| `order` | string | `asc` or `desc` |

**Response 200**
```json
{
  "success": true,
  "data": {
    "customers": [
      {
        "id": "cust_uuid_001",
        "name": "Priya Sharma",
        "phone": "+919876543210",
        "total_orders": 45,
        "ltv_rs": 14500,
        "last_order_at": "2026-07-20T15:30:00Z"
      }
    ]
  },
  "meta": { "page": 1, "limit": 20, "total": 1840 }
}
```

---

## Data Model

### Segment

| Field | Type | Constraints | Description |
|---|---|---|---|
| `id` | UUID v4 | PK | Internal identifier |
| `name` | VARCHAR(100) | UNIQUE, NOT NULL | Segment display name |
| `description` | TEXT | NULLABLE | Human-readable description |
| `segment_type` | ENUM | NOT NULL | `SYSTEM`, `CUSTOM` |
| `criteria` | JSONB | NULLABLE | Array of criterion objects |
| `customer_count` | INTEGER | DEFAULT 0 | Count at last compute |
| `avg_aov_rs` | DECIMAL(10,2) | NULLABLE | Average order value |
| `total_ltv_rs` | DECIMAL(14,2) | NULLABLE | Sum of LTV across members |
| `last_computed_at` | TIMESTAMPTZ | NULLABLE | Last successful compute |
| `created_by` | UUID | NULLABLE FK ? admin_users | Creator (null for SYSTEM) |
| `created_at` | TIMESTAMPTZ | DEFAULT NOW() | Creation timestamp |
| `updated_at` | TIMESTAMPTZ | DEFAULT NOW() | Last update |

### SegmentMembership

| Field | Type | Constraints | Description |
|---|---|---|---|
| `segment_id` | UUID | FK ? segments | Segment |
| `customer_id` | UUID | FK ? customers | Member customer |
| `added_at` | TIMESTAMPTZ | DEFAULT NOW() | When customer entered segment |
| PRIMARY KEY | `(segment_id, customer_id)` | | Composite key |

---

## Acceptance Criteria

1. All 8 system segments exist on a fresh environment and cannot be deleted or edited.
2. System segments are recomputed at 02:00 IST nightly; `last_computed_at` is updated after each run.
3. Creating a custom segment with valid criteria returns HTTP 201 and the segment appears in the list.
4. Custom segment with `has_rx_orders = true` and `loyalty_tier in [GOLD, PLATINUM]` correctly includes only customers matching both criteria.
5. Trigger compute endpoint returns HTTP 202 immediately with `job_id`; after job completes `customer_count` and `last_computed_at` are updated.
6. Deleting a system segment returns HTTP 403 `CANNOT_DELETE_SYSTEM_SEGMENT`.
7. Deleting a custom segment that is referenced by an active campaign returns HTTP 409 `SEGMENT_IN_USE`.
8. Segment detail returns `recommended_actions` as a non-empty string array.
9. `GET /api/v1/admin/segments/:id/customers` returns paginated customers sorted by requested field.
10. VIP segment count increases when a new customer reaches 30 orders or LTV exceeds Rs 10,000 after nightly recompute.

---

## Dependencies

| Dependency | Description |
|---|---|
| Customer Module | Customer records, order history, LTV data |
| Order Module | `total_orders`, `last_order_at`, `avg_order_value` |
| Loyalty Module (STORY-006) | `loyalty_tier` criterion field |
| Coupon Module (STORY-001) | `segment_ids` scope on coupons |
| Campaign Module (STORY-003) | `segment_id` on campaigns |
| Background Job Runner | Async compute and nightly recompute |

---

## Notes

- Segment computation is a read-heavy SQL aggregation over the `orders` and `customers` tables. For large datasets, the background worker uses cursor-based pagination to avoid timeouts.
- `growth_chart` in segment detail is a weekly rolling snapshot; historical data is stored in a `segment_snapshots` table keyed by `(segment_id, snapshot_date)`.
- `recommended_actions` are rule-based strings generated by the system based on segment characteristics (e.g. DORMANT ? suggest a win-back campaign).
