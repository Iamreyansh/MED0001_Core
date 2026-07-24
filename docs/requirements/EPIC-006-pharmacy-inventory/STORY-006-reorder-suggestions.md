# STORY-006: Reorder Suggestions - Auto-Reorder Intelligence

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-006 |
| **Epic** | EPIC-006 - Pharmacy Inventory |
| **Priority** | P1 |
| **Complexity** | L |
| **Status** | Draft |

---

## Overview

This story delivers the auto-reorder intelligence module available on Growth and Pro plans. The system analyses current stock levels against reorder thresholds nightly, scores each low-stock product against distributor price data, and surfaces actionable suggestions grouped by the cheapest available distributor. Pharmacists can review, adjust quantities, raise a Purchase Order (PO), and dispatch it to the distributor via WhatsApp or email - all from a single screen. Received POs convert directly into pre-filled GRNs, closing the procurement loop.

---

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| `pharmacy_owner` | Full read + write | View suggestions, create and send POs |
| `pharmacy_staff` | Read + draft PO | Can view suggestions and create DRAFT POs; cannot send |
| `admin_finance` | Read-only | Cross-pharmacy PO monitoring |
| `customer` | No access | Not applicable |

---

## Business Rules

1. **Growth+ plan gating.** All reorder endpoints return 403 `PLAN_FEATURE_LOCKED` for Free and Starter pharmacies.
2. **Nightly suggestion refresh.** The suggestion engine runs at 02:00 IST every night. It evaluates all `PharmacyProduct` records where `total_stock_units ? reorder_level` and `reorder_level > 0`. Results are stored in a snapshot table. Real-time refresh can be triggered manually via `POST /reorder/refresh`.
3. **Best distributor selection.** The "best distributor" for a suggestion row is determined by the lowest `effective_landed_cost` among all active distributors who supply that product (from `DistributorSupplyItem`). If no supply list entry exists, the suggestion shows "no distributor linked" and cannot form a PO automatically.
4. **Suggestions grouped by distributor.** The frontend groups suggestion rows by best distributor so the pharmacist can raise a single PO per distributor covering all their needed products.
5. **Days of cover calculation.** `days_of_cover = total_stock_units / avg_daily_units_sold_30d`. If `avg_daily_units_sold_30d = 0`, days_of_cover is shown as `?` (null in API).
6. **PO status lifecycle.** `DRAFT ? SENT ? RECEIVED`. A PO can only be moved to `RECEIVED` by recording a GRN (via `/record-grn`). Cancellation soft-deletes the PO (status = `CANCELLED`).
7. **PO dispatch channels.** A PO is sent to the distributor's registered `phone` (WhatsApp preferred, SMS fallback) or `email`. The message includes a PDF attachment with line items and totals. The `sent_at` timestamp is recorded on dispatch.
8. **PO to GRN conversion.** `POST /purchase-orders/:po_id/record-grn` creates a new `PurchaseGRN` record in `DRAFT` status with all PO line items pre-filled. The pharmacist then adjusts actuals (quantities may differ from ordered) and finalizes via the GRN flow.
9. **Open PO count KPI.** The KPI card tracks POs in `DRAFT` or `SENT` status that have not yet been converted to a GRN.

---

## API Endpoints

### 1. Reorder Suggestions List

```
GET /api/v1/pharmacy/reorder
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 30 req/min
**Plan:** Growth+

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `group_by` | enum | `distributor` | `distributor \| urgency` |
| `page` | integer | `1` | Page number |
| `limit` | integer | `50` | Items per page (max 200) |

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "kpi": {
      "items_below_reorder_level": 24,
      "distributors_to_order_from": 3,
      "estimated_savings": 4200.00,
      "open_pos_count": 2,
      "last_refreshed_at": "2026-07-24T02:00:00Z"
    },
    "suggestion_groups": [
      {
        "distributor_id": "uuid",
        "distributor_name": "Medico Pharma Distributors",
        "distributor_phone": "+919876543210",
        "items_count": 12,
        "estimated_po_value": 38400.00,
        "items": [
          {
            "product_id": "uuid",
            "product_name": "Paracetamol 500mg Tab",
            "manufacturer": "Cipla Ltd",
            "current_stock": 40,
            "reorder_level": 60,
            "days_of_cover": 3,
            "best_distributor_name": "Medico Pharma Distributors",
            "landed_price": 11.82,
            "best_price_badge": true,
            "savings_per_pack": 1.18,
            "suggested_quantity": 200,
            "alternative_distributor": {
              "name": "Apollo Pharma Dist.",
              "landed_price": 13.00
            }
          }
        ]
      }
    ]
  },
  "meta": { "page": 1, "limit": 50, "total": 24 }
}
```

---

### 2. Create Purchase Order

```
POST /api/v1/pharmacy/reorder/create-po
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 20 req/min
**Plan:** Growth+

**Request Body (application/json):**

```json
{
  "distributor_id": "UUID - required",
  "items": [
    {
      "product_id": "UUID - required",
      "quantity": "integer > 0 - required"
    }
  ]
}
```

**Success Response - 201 Created:**

```json
{
  "success": true,
  "data": {
    "po_id": "uuid",
    "po_number": "PO-2026-07-000018",
    "distributor_id": "uuid",
    "distributor_name": "Medico Pharma Distributors",
    "items_count": 12,
    "estimated_total": 38400.00,
    "status": "DRAFT",
    "created_at": "2026-07-24T11:30:00Z"
  }
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `EMPTY_ITEMS_LIST` | `items` array is empty |
| 400 | `DISTRIBUTOR_INACTIVE` | Distributor is deactivated |
| 403 | `PLAN_FEATURE_LOCKED` | Not on Growth+ |
| 404 | `DISTRIBUTOR_NOT_FOUND` | Distributor ID invalid |

---

### 3. List Purchase Orders

```
GET /api/v1/pharmacy/reorder/purchase-orders
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 60 req/min
**Plan:** Growth+

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `status` | enum | - | `DRAFT \| SENT \| RECEIVED \| CANCELLED` |
| `distributor_id` | UUID | - | Filter by distributor |
| `page` | integer | `1` | Page |
| `limit` | integer | `20` | Items per page |

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "purchase_orders": [
      {
        "po_id": "uuid",
        "po_number": "PO-2026-07-000018",
        "distributor_name": "Medico Pharma Distributors",
        "items_count": 12,
        "estimated_total": 38400.00,
        "status": "SENT",
        "created_at": "2026-07-24T11:30:00Z",
        "sent_at": "2026-07-24T12:00:00Z"
      }
    ]
  },
  "meta": { "page": 1, "limit": 20, "total": 5 }
}
```

---

### 4. Update Purchase Order (DRAFT only)

```
PATCH /api/v1/pharmacy/reorder/purchase-orders/:po_id
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 30 req/min
**Plan:** Growth+

**Request Body (application/json):**

```json
{
  "add_items": [
    { "product_id": "UUID", "quantity": "integer > 0" }
  ],
  "remove_item_ids": ["UUID", "UUID"],
  "update_items": [
    { "item_id": "UUID", "quantity": "integer > 0" }
  ]
}
```

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "po_id": "uuid",
    "items_count": 14,
    "estimated_total": 42800.00,
    "updated_at": "2026-07-24T11:45:00Z"
  }
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `PO_NOT_EDITABLE` | PO status is SENT or RECEIVED |
| 404 | `PO_NOT_FOUND` | PO ID not found |

---

### 5. Send Purchase Order

```
POST /api/v1/pharmacy/reorder/purchase-orders/:po_id/send
```

**Authentication:** Bearer JWT - `pharmacy_owner`
**Rate Limit:** 10 req/min
**Plan:** Growth+

**Request Body (application/json):**

```json
{
  "channel": "WHATSAPP | EMAIL - required",
  "recipient_override": "phone or email string - optional; uses distributor's registered contact if omitted"
}
```

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "po_id": "uuid",
    "po_number": "PO-2026-07-000018",
    "status": "SENT",
    "channel": "WHATSAPP",
    "sent_to": "+919876543210",
    "sent_at": "2026-07-24T12:00:00Z",
    "pdf_url": "https://cdn.medmate.in/pharmacy/uuid/PO-2026-07-000018.pdf"
  }
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `PO_ALREADY_SENT` | PO already in SENT status |
| 400 | `EMPTY_PO` | PO has no items |
| 403 | `STAFF_CANNOT_SEND_PO` | Only `pharmacy_owner` may send POs |
| 503 | `CHANNEL_UNAVAILABLE` | WhatsApp/Email service temporarily unavailable |

---

### 6. Record GRN from Received PO

```
POST /api/v1/pharmacy/reorder/purchase-orders/:po_id/record-grn
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 10 req/min
**Plan:** Growth+

**Request Body (application/json):**

```json
{
  "invoice_number": "string - required (distributor invoice number on received shipment)",
  "invoice_date": "date YYYY-MM-DD - required"
}
```

**Success Response - 201 Created:**

```json
{
  "success": true,
  "data": {
    "grn_id": "uuid",
    "grn_status": "DRAFT",
    "po_id": "uuid",
    "prefilled_items_count": 12,
    "message": "GRN created in DRAFT with PO items pre-filled. Review and finalize to update stock."
  }
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `PO_NOT_SENT` | PO must be in SENT status to record GRN |
| 400 | `DUPLICATE_INVOICE_NUMBER` | Invoice already recorded |
| 404 | `PO_NOT_FOUND` | PO ID not found |

---

### 7. Manual Refresh Suggestions

```
POST /api/v1/pharmacy/reorder/refresh
```

**Authentication:** Bearer JWT - `pharmacy_owner`
**Rate Limit:** 2 req/min (burst: 1 per 5 min)
**Plan:** Growth+

**Request Body:** None.

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "refreshed_at": "2026-07-24T12:30:00Z",
    "items_below_reorder_level": 24
  }
}
```

---

## Data Models

### PurchaseOrder

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID | PK | Unique PO ID |
| `pharmacy_id` | UUID | FK ? Pharmacy, NOT NULL | Owning pharmacy |
| `distributor_id` | UUID | FK ? Distributor, NOT NULL | Target distributor |
| `po_number` | VARCHAR(50) | NOT NULL, UNIQUE per pharmacy | Auto-generated PO number |
| `status` | ENUM | NOT NULL, default DRAFT | DRAFT / SENT / RECEIVED / CANCELLED |
| `created_by` | UUID | FK ? Staff | Creator staff ID |
| `sent_at` | TIMESTAMPTZ | nullable | Dispatch timestamp |
| `sent_channel` | ENUM | nullable | WHATSAPP / EMAIL |
| `grn_id` | UUID | FK ? PurchaseGRN, nullable | Linked GRN once received |
| `created_at` | TIMESTAMPTZ | NOT NULL | Creation timestamp |
| `updated_at` | TIMESTAMPTZ | NOT NULL | Last update |

### PurchaseOrderItem

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID | PK | Unique item ID |
| `po_id` | UUID | FK ? PurchaseOrder, NOT NULL | Parent PO |
| `product_id` | UUID | FK ? PharmacyProduct, NOT NULL | Product to order |
| `quantity` | INTEGER | > 0, NOT NULL | Ordered quantity |
| `estimated_price` | NUMERIC(10,2) | nullable | From supply list at time of PO creation |
| `created_at` | TIMESTAMPTZ | NOT NULL | Item creation time |

### ReorderSuggestionSnapshot

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID | PK | Snapshot record |
| `pharmacy_id` | UUID | NOT NULL | Pharmacy context |
| `product_id` | UUID | FK ? PharmacyProduct | Product below reorder level |
| `current_stock` | INTEGER | NOT NULL | Stock at snapshot time |
| `reorder_level` | INTEGER | NOT NULL | Reorder threshold |
| `days_of_cover` | NUMERIC(5,1) | nullable | Computed days of cover |
| `best_distributor_id` | UUID | nullable | Cheapest distributor |
| `landed_price` | NUMERIC(10,2) | nullable | Best landed cost |
| `snapshot_date` | DATE | NOT NULL | Date of snapshot |
| `created_at` | TIMESTAMPTZ | NOT NULL | Record creation |

---

## Acceptance Criteria

- [ ] Given a Starter-plan pharmacy JWT, when `GET /api/v1/pharmacy/reorder` is called, then a 403 `PLAN_FEATURE_LOCKED` response is returned.
- [ ] Given 24 products below reorder level, when `GET /reorder` is called, then the `kpi.items_below_reorder_level` equals 24 and suggestions are grouped by best distributor.
- [ ] Given `POST /reorder/create-po` with 12 items for distributor A, then a PO in `DRAFT` status is created with `po_number` in the format `PO-YYYY-MM-NNNNNN`.
- [ ] Given a DRAFT PO, when `PATCH /purchase-orders/:po_id` adds 2 items and removes 1, then `items_count` reflects the net change.
- [ ] Given `POST /purchase-orders/:po_id/send`, when the PO is already in `SENT` status, then a 400 `PO_ALREADY_SENT` error is returned.
- [ ] Given `POST /purchase-orders/:po_id/record-grn` on a SENT PO, then a new DRAFT GRN is created with all PO items pre-filled and a `grn_id` is returned.
- [ ] Given `POST /reorder/refresh` called twice within 5 minutes, then the second call returns 429 rate-limit error.
- [ ] Given a product with `avg_daily_units_sold_30d = 0`, when the suggestion list is fetched, then `days_of_cover` is returned as `null` in the suggestion row.

---

## Dependencies

- **EPIC-006 / STORY-001 (Inventory):** `reorder_level` and `total_stock_units` are source data for suggestion generation.
- **EPIC-006 / STORY-004 (GRN):** `/record-grn` creates a new GRN in the purchase flow.
- **EPIC-006 / STORY-005 (Distributors):** `DistributorSupplyItem` provides `effective_landed_cost` for best-distributor selection.
- **EPIC-010 (Notifications):** WhatsApp/Email PO dispatch uses the notification service.
- **Plan Gating Middleware:** All endpoints validate Growth+ plan.

---

## Notes

- The PO number format is `PO-{YYYY}-{MM}-{NNNNNN}` with a per-pharmacy sequence counter reset monthly.
- The nightly suggestion refresh cron job should be idempotent - re-running for the same date replaces existing `ReorderSuggestionSnapshot` records for that pharmacy.
- `estimated_savings` in KPI is computed as: for each item, `(alternative_distributor_landed_cost - best_distributor_landed_cost) - suggested_quantity`. Sum across all items.
- PO PDF is generated server-side using the same PDF infrastructure as GRN and invoices. It includes pharmacy letterhead, distributor address, line items with estimated prices, and a total row.
