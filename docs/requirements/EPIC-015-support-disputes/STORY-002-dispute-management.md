# STORY-002: Dispute Management

| Field | Value |
|---|---|
| Story ID | EPIC-015-STORY-002 |
| Epic | EPIC-015 Support and Disputes |
| Title | Dispute Management |
| Priority | P0 |
| Status | Planned |
| Created | 2026-07-24 |
| Last Updated | 2026-07-24 |

---

## Overview

Dispute Management handles customer complaints tied to specific orders that require structured investigation and a formal liability determination. Disputes cover seven complaint types - wrong items, missing items, damaged goods, not delivered, expired medicine, quality issues, and overcharging. The system provides a system-recommended liable party based on dispute type, an evidence panel for customer-uploaded proof, and two resolution paths (approve refund or reject). Refunds ? Rs 200 are auto-processed; larger amounts require `admin_support` approval. Admin HQ has a dedicated dispute queue with exposure metrics to monitor financial risk.

---

## User Roles

| Role | Capability |
|---|---|
| `admin_super` | Full access; approve/reject all disputes |
| `admin_operations` | Investigate, approve (? Rs 200 auto; > Rs 200 requires approval) |
| `admin_support` | Investigate and resolve disputes; approve refunds > Rs 200 |
| `customer` | Raise disputes on own orders; view own dispute history |

---

## Business Rules

1. **One dispute per order** - only one dispute can be raised per order; attempting to create a second returns `DISPUTE_ALREADY_EXISTS`.
2. **Liability system recommendation** - the system recommends a liable party based on `dispute_type`: WRONG_ITEMS/MISSING_ITEMS/DAMAGED/EXPIRED_MEDICINE/QUALITY ? PHARMACY; NOT_DELIVERED (tracking shows delivery) ? RIDER; OVERCHARGED ? PLATFORM. Admin can override.
3. **Auto-raise for non-delivery** - if an order has `status = DELIVERED` but the customer reports non-delivery, a dispute is auto-raised with `dispute_type = NOT_DELIVERED` after the customer's report is received.
4. **Refund auto-processing** - approved refunds ? Rs 200 are automatically processed to the customer's source payment method or wallet (admin choice at approval); no additional approval required.
5. **Refund approval gate** - approved disputes with `refund_amount > Rs 200` require explicit `admin_support` approval; they cannot be auto-processed.
6. **Resolution SLA** - disputes must be resolved within 48 hours of creation; breach triggers escalation.
7. **Disputed order banner** - in the admin order detail view, a banner is displayed if an open or resolved dispute exists for the order.
8. **Evidence submission** - customers submit evidence (photos, screenshots) via `evidence_urls` at dispute creation; additional evidence cannot be added after submission (v1).
9. **Refund destination** - refund can go to `SOURCE` (original payment method) or `WALLET` (customer wallet); admin decides at resolution.
10. **Dispute status flow** - OPEN ? INVESTIGATING ? RESOLVED or CLOSED; disputes cannot be reopened once CLOSED.

---

## Dispute Type Liability Matrix

| Dispute Type | System Recommended Liable Party | Rationale |
|---|---|---|
| WRONG_ITEMS | PHARMACY | Pharmacy fulfilled incorrectly |
| MISSING_ITEMS | PHARMACY | Pharmacy packed incorrectly |
| DAMAGED | PHARMACY | Pharmacy responsible for packaging |
| EXPIRED_MEDICINE | PHARMACY | Pharmacy sold expired stock |
| QUALITY | PHARMACY | Pharmacy quality control failure |
| NOT_DELIVERED | RIDER | Delivery not completed (if tracking shows delivered) |
| OVERCHARGED | PLATFORM | Billing discrepancy on platform side |

---

## API Endpoints

### 1. List Disputes (Admin)

```
GET /api/v1/admin/support/disputes
Authorization: Bearer JWT (admin_super | admin_operations | admin_support)
```

**Query Parameters**

| Parameter | Type | Description |
|---|---|---|
| `status` | string | `OPEN`, `INVESTIGATING`, `RESOLVED`, `CLOSED` |
| `liable_party` | string | `PHARMACY`, `RIDER`, `PLATFORM`, `CUSTOMER` |
| `dispute_type` | string | Filter by type |
| `page` | integer | Default 1 |
| `limit` | integer | Default 20 |
| `export` | boolean | `true` for CSV export |

**Response 200**
```json
{
  "success": true,
  "data": {
    "chips": {
      "open_disputes": 14,
      "refund_exposure_rs": 42800,
      "avg_resolution_hours": 18.4,
      "resolved_today": 6
    },
    "disputes": [
      {
        "id": "dsp_uuid_001",
        "order_id": "ord_uuid_001",
        "customer_name": "Priya Sharma",
        "dispute_type": "WRONG_ITEMS",
        "status": "OPEN",
        "liable_party": null,
        "refund_amount_rs": null,
        "created_at": "2026-07-24T10:00:00Z"
      }
    ]
  },
  "meta": { "page": 1, "limit": 20, "total": 14 }
}
```

---

### 2. Create Dispute (Customer)

```
POST /api/v1/support/disputes
Authorization: Bearer JWT (customer)
Content-Type: application/json
```

**Request Body**
```json
{
  "order_id": "ord_uuid_001",
  "dispute_type": "WRONG_ITEMS",
  "description": "I received ibuprofen 400mg instead of the paracetamol 500mg I ordered.",
  "evidence_urls": [
    "https://cdn.nammamedmate.com/evidence/img_dispute_001.jpg"
  ]
}
```

**Response 201**
```json
{
  "success": true,
  "data": {
    "id": "dsp_uuid_001",
    "dispute_id": "DSP-20260724-000014",
    "order_id": "ord_uuid_001",
    "dispute_type": "WRONG_ITEMS",
    "status": "OPEN",
    "resolution_sla_at": "2026-07-26T10:00:00Z",
    "created_at": "2026-07-24T10:00:00Z",
    "message": "Your dispute has been raised. We will resolve it within 48 hours."
  }
}
```

**Error Responses**

| HTTP | Error Code | Description |
|---|---|---|
| 409 | `DISPUTE_ALREADY_EXISTS` | Order already has a dispute |
| 404 | `ORDER_NOT_FOUND` | Order does not belong to customer |
| 422 | `ORDER_NOT_ELIGIBLE` | Order status does not allow dispute (e.g. not DELIVERED) |

---

### 3. Get Dispute Detail (Admin)

```
GET /api/v1/admin/support/disputes/:id
Authorization: Bearer JWT (admin_super | admin_operations | admin_support)
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "id": "dsp_uuid_001",
    "dispute_id": "DSP-20260724-000014",
    "dispute_type": "WRONG_ITEMS",
    "status": "INVESTIGATING",
    "description": "Customer received wrong items.",
    "evidence_urls": ["https://cdn.nammamedmate.com/evidence/img_dispute_001.jpg"],
    "order_context": {
      "order_id": "ord_uuid_001",
      "order_items": [
        { "name": "Paracetamol 500mg", "qty": 2, "price_rs": 48 }
      ],
      "pharmacy_name": "Apollo Pharmacy HSR",
      "rider_name": "Kiran Raj",
      "delivery_tracking_url": "https://tracking.nammamedmate.com/ord_uuid_001"
    },
    "liability_recommendation": {
      "recommended_liable_party": "PHARMACY",
      "rationale": "WRONG_ITEMS disputes are attributed to pharmacy fulfilment error."
    },
    "system_refund_recommendation": {
      "refund_amount_rs": 96,
      "refund_to": "SOURCE",
      "auto_process": true,
      "note": "Amount ? Rs 200; eligible for auto-processing."
    },
    "history": [
      { "event": "DISPUTE_RAISED", "at": "2026-07-24T10:00:00Z", "actor": "Priya Sharma" },
      { "event": "INVESTIGATION_STARTED", "at": "2026-07-24T10:30:00Z", "actor": "Ravi Kumar" }
    ],
    "investigated_by": "admin_uuid_002",
    "created_at": "2026-07-24T10:00:00Z"
  }
}
```

---

### 4. Mark as Investigating (Admin)

```
POST /api/v1/admin/support/disputes/:id/investigate
Authorization: Bearer JWT (admin_super | admin_operations | admin_support)
Content-Type: application/json
```

**Request Body**
```json
{
  "assigned_to": "admin_uuid_002",
  "notes": "Contacted pharmacy to verify the order packing records."
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "id": "dsp_uuid_001",
    "status": "INVESTIGATING",
    "assigned_to": "admin_uuid_002",
    "updated_at": "2026-07-24T10:30:00Z"
  }
}
```

---

### 5. Approve Dispute and Refund (Admin)

```
POST /api/v1/admin/support/disputes/:id/resolve-approve
Authorization: Bearer JWT (admin_super | admin_support)
Content-Type: application/json
```

**Request Body**
```json
{
  "liable_party": "PHARMACY",
  "refund_amount": 96,
  "refund_to": "SOURCE",
  "resolution_notes": "Pharmacy confirmed packing error. Full refund processed."
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "id": "dsp_uuid_001",
    "status": "RESOLVED",
    "liable_party": "PHARMACY",
    "refund_amount_rs": 96,
    "refund_to": "SOURCE",
    "auto_processed": true,
    "refund_transaction_id": "txn_uuid_001",
    "resolved_at": "2026-07-24T11:00:00Z"
  }
}
```

**Error Responses**

| HTTP | Error Code | Description |
|---|---|---|
| 403 | `APPROVAL_REQUIRED` | Refund > Rs 200 requires `admin_support` role |
| 422 | `INVALID_REFUND_AMOUNT` | Amount exceeds order value |

---

### 6. Reject Dispute (Admin)

```
POST /api/v1/admin/support/disputes/:id/resolve-reject
Authorization: Bearer JWT (admin_super | admin_support)
Content-Type: application/json
```

**Request Body**
```json
{
  "rejection_reason": "Evidence insufficient. Delivery tracking confirms correct items dispatched.",
  "notes": "Pharmacy provided warehouse CCTV evidence of correct packing."
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "id": "dsp_uuid_001",
    "status": "RESOLVED",
    "liable_party": "CUSTOMER",
    "refund_amount_rs": 0,
    "resolved_at": "2026-07-24T11:30:00Z"
  }
}
```

---

### 7. Customer - View Own Disputes

```
GET /api/v1/customers/me/disputes
Authorization: Bearer JWT (customer)
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "disputes": [
      {
        "id": "dsp_uuid_001",
        "dispute_id": "DSP-20260724-000014",
        "order_id": "ord_uuid_001",
        "dispute_type": "WRONG_ITEMS",
        "status": "RESOLVED",
        "refund_amount_rs": 96,
        "refund_to": "SOURCE",
        "created_at": "2026-07-24T10:00:00Z",
        "resolved_at": "2026-07-24T11:00:00Z"
      }
    ]
  },
  "meta": { "page": 1, "limit": 20, "total": 1 }
}
```

---

## Data Model

### Dispute

| Field | Type | Constraints | Description |
|---|---|---|---|
| `id` | UUID v4 | PK | Internal identifier |
| `dispute_id` | VARCHAR(22) | UNIQUE, NOT NULL | Human-readable ID |
| `order_id` | UUID | FK ? orders, UNIQUE | One dispute per order |
| `customer_id` | UUID | FK ? customers | Disputing customer |
| `dispute_type` | ENUM | NOT NULL | `WRONG_ITEMS`, `MISSING_ITEMS`, `DAMAGED`, `NOT_DELIVERED`, `EXPIRED_MEDICINE`, `QUALITY`, `OVERCHARGED` |
| `description` | TEXT | NOT NULL | Customer's description |
| `evidence_urls` | TEXT[] | DEFAULT {} | Evidence file URLs |
| `status` | ENUM | DEFAULT OPEN | `OPEN`, `INVESTIGATING`, `RESOLVED`, `CLOSED` |
| `liable_party` | ENUM | NULLABLE | `PHARMACY`, `RIDER`, `PLATFORM`, `CUSTOMER` |
| `refund_amount_rs` | DECIMAL(10,2) | NULLABLE | Approved refund amount |
| `refund_to` | ENUM | NULLABLE | `SOURCE`, `WALLET` |
| `resolution_notes` | TEXT | NULLABLE | Admin resolution summary |
| `rejection_reason` | TEXT | NULLABLE | Rejection reason if rejected |
| `investigated_by` | UUID | NULLABLE FK ? admin_users | Assigned investigator |
| `resolved_at` | TIMESTAMPTZ | NULLABLE | Resolution timestamp |
| `resolution_sla_at` | TIMESTAMPTZ | NOT NULL | SLA deadline (created_at + 48h) |
| `created_at` | TIMESTAMPTZ | DEFAULT NOW() | Dispute creation time |

---

## Acceptance Criteria

1. Customer raises a WRONG_ITEMS dispute; system recommends liable party PHARMACY in the admin detail view.
2. Creating a second dispute on the same order returns HTTP 409 `DISPUTE_ALREADY_EXISTS`.
3. Approving a refund of Rs 96 (? Rs 200) auto-processes the refund and returns `auto_processed: true`.
4. Attempting to approve a refund of Rs 250 (> Rs 200) without `admin_support` role returns HTTP 403 `APPROVAL_REQUIRED`.
5. Admin rejects a dispute; customer's dispute history shows status RESOLVED with `refund_amount_rs = 0`.
6. Dispute detail shows the full history timeline with actor names and timestamps.
7. `resolution_sla_at` = `created_at + 48 hours`; breach triggers escalation.
8. Order detail view shows a dispute banner when an open or resolved dispute exists for the order.
9. Auto-raise for NOT_DELIVERED is triggered when customer reports non-delivery despite `order.status = DELIVERED`.
10. Admin export of disputes as CSV includes all key fields: dispute_id, order_id, customer_name, type, status, liable_party, refund_amount.

---

## Dependencies

| Dependency | Description |
|---|---|
| Order Module | Order context, delivery tracking status |
| Finance / Refund Module | Refund processing (auto and manual) |
| Customer Wallet | Wallet refund destination |
| Ticket Module (STORY-001) | Dispute can be linked to a support ticket |
| Rider Tracking | Delivery confirmation for NOT_DELIVERED disputes |
| Notification Engine | Dispute status updates to customer |

---

## Notes

- Dispute ID format: `DSP-YYYYMMDD-XXXXXX`.
- The `liable_party = CUSTOMER` outcome (used when rejecting) is recorded for analytics to track abuse patterns; it does not result in any charge to the customer.
- Pharmacy deductions for approved PHARMACY-liable disputes are managed separately in the finance/payout module.
