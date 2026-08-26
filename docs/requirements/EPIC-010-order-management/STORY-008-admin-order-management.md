# STORY-008: Admin Order Management and Oversight

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-008 |
| **Epic** | EPIC-010 - Order Management |
| **Priority** | P0 |
| **Complexity** | XL |
| **Status** | Draft |

---

## Overview

This story defines the admin command centre for order oversight and intervention - the primary tool used by `admin_operations` and `admin_super` to monitor the live order fleet, intervene in problem orders, manage disputes, and track GMV and commission. The admin order list provides real-time summary chips (GMV, live orders, SLA risk), segmented filter views (ALL, LIVE, SLA_RISK, DISPUTES, DELIVERED, CANCELLED), full-text search, and CSV export. The order detail view gives a complete operational picture: status timeline, customer context, bill breakdown with commission, payment details, prescription card, rider management, and dispute/note capabilities. The live feed endpoint powers the real-time command dashboard.

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| `admin_super` | Full access | All actions including refund, cancel, reassign |
| `admin_operations` | Full access | All actions except financial settlement |
| `admin_finance` | Read + refund | View orders, issue refunds; cannot cancel |
| `admin_support` | Read + dispute + note | View orders, flag disputes, add notes |
| `admin_compliance` | Read (no Rx content) | View order context for compliance audit |
| `customer` | None | Cannot access admin endpoints |

---

## Business Rules

1. **SLA chip segmentation:** The `SLA_RISK` segment shows all in-progress orders with < 5 minutes remaining on their 30-minute SLA. Orders past the SLA deadline are marked `SLA_BREACHED` and flagged with a visual indicator (not a separate segment, but a badge on the order card in LIVE).
2. **Commission per order:** `commission = GMV - commission_rate_pct`. `commission_rate_pct` is the platform's configured commission percentage for the pharmacy (stored on `pharmacy.commission_rate`). The commission is displayed per order in the admin detail and as a total in the summary chips.
3. **Prescription content restriction:** Admin roles (`admin_operations`, `admin_finance`, `admin_support`) cannot view the prescription file content (image/PDF) on an order. The order detail shows a "Prescription attached" card with the prescription ID and type only. Only `admin_compliance` can view prescription content via the EPIC-008 compliance audit interface.
4. **Dispute flag logic:** When an order is flagged as disputed (`POST /api/v1/admin/orders/:order_id/dispute`), a prominent banner appears on the order detail view for all admin roles. Disputes are not auto-resolved; a human must update the order note or resolve via the cancel/refund endpoints.
5. **Internal notes visibility:** Notes added via `POST /api/v1/admin/orders/:order_id/note` are visible only to admin team members. Pinned notes appear at the top of the notes list. Notes are append-only (no deletion).
6. **Rider reassignment:** A rider can be reassigned at any point before `DELIVERED`. The `PATCH /api/v1/admin/orders/:order_id/rider` endpoint replaces the current rider assignment and creates a new `OrderStatusEvent` entry.
7. **Live feed for command dashboard:** `GET /api/v1/admin/orders/live-feed` returns all in-progress orders sorted by SLA risk (ascending time remaining). This endpoint is optimised for sub-200ms response and should be backed by a cache with a 10-second TTL.
8. **CSV export:** The export includes all fields visible in the order list plus bill breakdown, payment method, payment status, and commission. Exports over 10,000 rows are processed asynchronously with a download link.

---

## API Endpoints

### 1. List Admin Orders

```GET /api/v1/admin/orders```

**Authentication:** Bearer JWT - `admin_super` | `admin_operations` | `admin_finance` | `admin_support` | `admin_compliance`
**Rate Limit:** 30 req/min

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `segment` | string | `ALL` | `ALL`, `LIVE`, `SLA_RISK`, `DISPUTES`, `DELIVERED`, `CANCELLED` |
| `search` | string | - | Order ID, customer name, customer phone, area, pharmacy |
| `pharmacy_id` | UUID | - | Filter by pharmacy |
| `rider_id` | UUID | - | Filter by rider |
| `zone_id` | UUID | - | Filter by delivery zone |
| `payment_method` | ENUM | - | `UPI`, `CARD`, `COD`, `WALLET` |
| `is_rx_only` | boolean | - | Filter prescription-only orders |
| `from_date` | date | - | Created from date |
| `to_date` | date | - | Created to date |
| `page` | integer | 1 | Pagination |
| `limit` | integer | 20 | Max 100 |
| `export` | boolean | false | If true, returns async CSV download URL |

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "summary": {
      "total_orders": 1247,
      "live_now": 34,
      "sla_risk": 3,
      "gmv": 284650.00,
      "commission": 28465.00,
      "aov": 228.27
    },
    "orders": [
      {
        "order_id": "ord_01J3KP7VDEF789",
        "order_number": "ORD-20260724-00123",
        "customer_name": "Ravi Kumar",
        "customer_phone": "+91-9876543210",
        "pharmacy_name": "Sai Medicals",
        "area": "Koramangala",
        "status": "OUT_FOR_DELIVERY",
        "sla_remaining_minutes": 7,
        "sla_breached": false,
        "is_disputed": false,
        "total": 221.25,
        "commission": 22.13,
        "payment_method": "UPI",
        "payment_status": "PAID",
        "has_prescription": true,
        "created_at": "2026-07-24T11:30:00Z"
      }
    ]
  },
  "meta": {
    "page": 1,
    "limit": 20,
    "total": 1247,
    "total_pages": 63
  }
}
```

---

### 2. Get Admin Order Detail

```GET /api/v1/admin/orders/:order_id```

**Authentication:** Bearer JWT - `admin_super` | `admin_operations` | `admin_finance` | `admin_support` | `admin_compliance`
**Rate Limit:** 60 req/min

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "order_id": "ord_01J3KP7VDEF789",
    "order_number": "ORD-20260724-00123",
    "status": "OUT_FOR_DELIVERY",
    "is_disputed": false,
    "dispute_banner": null,
    "status_timeline": [
      { "status": "PENDING_ACCEPTANCE", "timestamp": "2026-07-24T11:30:00Z", "actor": "system" },
      { "status": "ACCEPTED", "timestamp": "2026-07-24T11:35:00Z", "actor": "pharmacy" },
      { "status": "PACKING", "timestamp": "2026-07-24T11:37:00Z", "actor": "pharmacy" },
      { "status": "READY_FOR_PICKUP", "timestamp": "2026-07-24T11:42:00Z", "actor": "pharmacy" },
      { "status": "OUT_FOR_DELIVERY", "timestamp": "2026-07-24T11:45:00Z", "actor": "rider" }
    ],
    "customer": {
      "id": "cust_01J3KP7VAAA111",
      "name": "Ravi Kumar",
      "phone": "+91-9876543210",
      "order_count": 16,
      "ltv": 3420.00
    },
    "pharmacy": {
      "id": "ph_01J3KP7VFFF666",
      "name": "Sai Medicals",
      "area": "Koramangala",
      "commission_rate": 10.0
    },
    "items": [
      { "name": "Metformin 500mg (Glycomet)", "quantity": 3, "unit_price": 85.00, "line_total": 255.00 }
    ],
    "bill": {
      "item_total": 255.00,
      "coupon_code": "NAMMA25",
      "coupon_discount": 63.75,
      "subtotal_after_discount": 191.25,
      "delivery_fee": 25.00,
      "handling_fee": 5.00,
      "wallet_applied": 0.00,
      "total_payable": 221.25,
      "commission_amount": 22.13,
      "commission_rate_pct": 10.0
    },
    "payment": {
      "method": "UPI",
      "status": "PAID",
      "transaction_id": "pay_Cashfree98765",
      "gateway_order_id": "order_Cashfree12345"
    },
    "prescription_card": {
      "id": "rx_01J3KP7VLLL222",
      "type": "E_PRESCRIPTION",
      "status": "VERIFIED",
      "note": "Prescription content restricted. View via Compliance Audit (EPIC-008)."
    },
    "delivery_partner": {
      "rider_id": "rider_01J3KP7VUUU111",
      "name": "Suresh Kumar",
      "phone": "+91-9988776655",
      "vehicle_plate": "KA01AB1234",
      "otp_verified": false
    },
    "customer_rating": null,
    "internal_notes": [
      {
        "note_id": "note_01J3KP7VYYY555",
        "note": "Customer called to confirm delivery address is accurate.",
        "added_by": "admin_01J3KP7VEEE555",
        "added_by_name": "Priya Support",
        "is_pinned": true,
        "created_at": "2026-07-24T11:40:00Z"
      }
    ],
    "sla_deadline": "2026-07-24T12:01:00Z",
    "sla_remaining_minutes": 6,
    "sla_breached": false,
    "created_at": "2026-07-24T11:30:00Z"
  }
}
```

---

### 3. Reassign Rider

```PATCH /api/v1/admin/orders/:order_id/rider```

**Authentication:** Bearer JWT - `admin_super` | `admin_operations`
**Rate Limit:** 10 req/min

**Request Body:**
```json
{
  "rider_id": "rider_01J3KP7VZZZ666",
  "reason": "Original rider unavailable due to accident"
}
```

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "order_id": "ord_01J3KP7VDEF789",
    "new_rider_id": "rider_01J3KP7VZZZ666",
    "new_rider_name": "Mahesh Reddy",
    "previous_rider_id": "rider_01J3KP7VUUU111",
    "reassigned_at": "2026-07-24T11:50:00Z"
  }
}
```

---

### 4. Flag Order as Disputed

```POST /api/v1/admin/orders/:order_id/dispute```

**Authentication:** Bearer JWT - `admin_super` | `admin_operations` | `admin_support`
**Rate Limit:** 10 req/min

**Request Body:**
```json
{
  "reason": "Customer claims medicines were not delivered despite OTP verification",
  "liable_party": "RIDER"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `reason` | string | Yes | Dispute description (max 500 chars) |
| `liable_party` | ENUM | Yes | `PHARMACY`, `RIDER`, `PLATFORM`, `CUSTOMER` |

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "order_id": "ord_01J3KP7VDEF789",
    "is_disputed": true,
    "dispute_reason": "Customer claims medicines were not delivered...",
    "liable_party": "RIDER",
    "flagged_by": "admin_01J3KP7VEEE555",
    "flagged_at": "2026-07-24T13:00:00Z"
  }
}
```

---

### 5. Add Internal Note

```POST /api/v1/admin/orders/:order_id/note```

**Authentication:** Bearer JWT - `admin_super` | `admin_operations` | `admin_support` | `admin_finance`
**Rate Limit:** 20 req/min

**Request Body:**
```json
{
  "note": "Customer contacted via WhatsApp. Confirmed delivery address is correct.",
  "is_pinned": true
}
```

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "note_id": "note_01J3KP7VYYY555",
    "order_id": "ord_01J3KP7VDEF789",
    "note": "Customer contacted via WhatsApp. Confirmed delivery address is correct.",
    "is_pinned": true,
    "added_by": "admin_01J3KP7VEEE555",
    "created_at": "2026-07-24T11:40:00Z"
  }
}
```

---

### 6. Live Order Feed

```GET /api/v1/admin/orders/live-feed```

**Authentication:** Bearer JWT - `admin_super` | `admin_operations`
**Rate Limit:** 60 req/min (10-second polling intended)

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "total_live": 34,
    "sla_risk_count": 3,
    "sla_breached_count": 0,
    "last_updated_at": "2026-07-24T11:50:00Z",
    "orders": [
      {
        "order_id": "ord_01J3KP7VAAA000",
        "order_number": "ORD-20260724-00121",
        "status": "OUT_FOR_DELIVERY",
        "pharmacy_name": "Sai Medicals",
        "area": "Koramangala",
        "customer_name": "Meena Iyer",
        "sla_remaining_minutes": 2,
        "sla_risk": true,
        "sla_breached": false,
        "is_disputed": false,
        "total": 185.00
      },
      {
        "order_id": "ord_01J3KP7VDEF789",
        "order_number": "ORD-20260724-00123",
        "status": "OUT_FOR_DELIVERY",
        "pharmacy_name": "Sai Medicals",
        "area": "Koramangala",
        "customer_name": "Ravi Kumar",
        "sla_remaining_minutes": 7,
        "sla_risk": false,
        "sla_breached": false,
        "is_disputed": false,
        "total": 221.25
      }
    ]
  }
}
```

---

## Data Models

### OrderDispute

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID v4 | PK, NOT NULL | Dispute record identifier |
| `order_id` | UUID | FK ? orders.id, NOT NULL | Disputed order |
| `reason` | string | NOT NULL, max 500 | Dispute description |
| `liable_party` | ENUM | NOT NULL | `PHARMACY`, `RIDER`, `PLATFORM`, `CUSTOMER` |
| `flagged_by` | UUID | FK ? users.id, NOT NULL | Admin who flagged |
| `flagged_at` | timestamp | NOT NULL | Flag timestamp |
| `resolved` | boolean | default false | Whether dispute is resolved |
| `resolved_at` | timestamp | nullable | Resolution timestamp |
| `resolution_notes` | string | nullable | How it was resolved |

### OrderNote

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID v4 | PK, NOT NULL | Note identifier |
| `order_id` | UUID | FK ? orders.id, NOT NULL | Parent order |
| `note` | text | NOT NULL, max 2000 | Note content |
| `is_pinned` | boolean | default false | Pinned notes show first |
| `added_by` | UUID | FK ? users.id, NOT NULL | Admin author |
| `created_at` | timestamp | NOT NULL | Immutable creation time |

### AdminOrderExportJob

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID v4 | PK | Export job identifier |
| `requested_by` | UUID | FK ? users.id, NOT NULL | Admin who requested |
| `filters` | JSONB | NOT NULL | Applied filter parameters |
| `row_count` | integer | nullable | Row count on completion |
| `status` | ENUM | NOT NULL | `PENDING`, `PROCESSING`, `READY`, `FAILED` |
| `s3_key` | string | nullable | Download file key |
| `download_url` | string | nullable (virtual) | Time-limited signed URL |
| `created_at` | timestamp | NOT NULL | Request time |
| `completed_at` | timestamp | nullable | Completion time |

---

## Acceptance Criteria

- [ ] **Given** `admin_operations` calls `GET /api/v1/admin/orders` with `segment: SLA_RISK`, **when** the response is received, **then** only in-progress orders with `sla_remaining_minutes < 5` are returned.
- [ ] **Given** `admin_compliance` calls `GET /api/v1/admin/orders/:order_id`, **when** the detail is returned, **then** the `prescription_card` field shows only the prescription ID and type, with no file URL (content restricted).
- [ ] **Given** `admin_support` calls `POST /api/v1/admin/orders/:order_id/dispute`, **when** the dispute is flagged, **then** the order detail for all admin roles shows a `dispute_banner` with the reason and `liable_party`.
- [ ] **Given** `admin_finance` calls `POST /api/v1/admin/orders/:order_id/note`, **when** the note is added, **then** the note is visible in the `internal_notes` array and is NOT visible to the customer.
- [ ] **Given** a note is added to an order, **when** an admin attempts to delete it, **then** the API returns HTTP 405 Method Not Allowed (notes are append-only).
- [ ] **Given** `GET /api/v1/admin/orders/live-feed` is polled at 10-second intervals, **when** an order's `sla_remaining_minutes` drops below 5, **then** the next poll response includes that order with `sla_risk: true` at the top of the list.
- [ ] **Given** an export is requested for 15,000 orders, **when** the export is triggered, **then** the API returns a job ID and status `PROCESSING` (async), and the download URL is available within 120 seconds.
- [ ] **Given** `admin_super` reassigns a rider on an `OUT_FOR_DELIVERY` order, **when** the reassignment succeeds, **then** an `OrderStatusEvent` is recorded with `actor_type: ADMIN` and the new rider's details.

---

## Dependencies

| Dependency | Story / System | Notes |
|------------|---------------|-------|
| EPIC-010 STORY-005 - Order lifecycle | Upstream | Order status and SLA data |
| EPIC-010 STORY-006 - Cancellation/refund | Downstream | Admin cancel and refund via separate endpoints |
| EPIC-011 - Rider management | Upstream | Rider lookup for reassignment |
| EPIC-008 STORY-003 - Compliance audit | Adjacent | Prescription content routed to compliance, not here |
| S3 export bucket | Infrastructure | CSV exports stored async |
| Auth / RBAC | EPIC-001 | Multi-role enforcement per endpoint |

---

## Notes

- The live feed endpoint must be backed by a Redis cache (10-second TTL) to handle high-frequency polling from the command centre dashboard without hitting the database on every call.
- Commission amount shown in admin is **informational only** - it does not trigger any financial settlement. Settlement is handled by the finance module (EPIC-012).
- `GMV` in summary chips = `sum(total_payable)` for all orders in the selected date range with status ? `CANCELLED`. `AOV` (Average Order Value) = `GMV / total_orders`.
- The `prescription_card` restriction (no file URL for most admin roles) is enforced server-side - the file URL is not included in the serialisation for roles without `admin_compliance` access.
