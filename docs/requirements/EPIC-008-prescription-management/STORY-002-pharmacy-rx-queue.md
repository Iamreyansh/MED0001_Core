# STORY-002: Pharmacy Prescription Review and Dispense Workflow

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-002 |
| **Epic** | EPIC-008 - Prescription Management |
| **Priority** | P0 |
| **Complexity** | L |
| **Status** | Draft |

---

## Overview

This story defines the pharmacy-side workflow for reviewing, approving, rejecting, and dispensing prescriptions received through Namma MedMate. When a customer attaches a prescription to an order or submits an Rx quote request, the associated prescription lands in the pharmacy's Rx queue. Pharmacists (on Starter plan and above) must review uploaded prescriptions manually and can auto-trust verified e-prescriptions from the teleconsult service. The queue enforces a 2-hour SLA from receipt to review, with overdue alerts surfaced via dashboard KPIs and WhatsApp notifications. Approved prescriptions directly drive the order's line items and can be pushed to the POS billing cart for seamless dispensing.

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| `pharmacy_owner` | Full access | All queue actions, view KPIs, approve/reject/dispense |
| `pharmacy_staff` | Full access (restricted to own pharmacy) | Same as owner, limited to assigned pharmacy |
| `admin_super` | Read | Can view any pharmacy's Rx queue for oversight |
| `admin_compliance` | Read | Can view for audit purposes |
| `customer` | None | Cannot access pharmacy Rx queue |

---

## Business Rules

1. **SLA enforcement:** The review SLA is 2 hours from the time the prescription is received in the queue (`received_at`). If a prescription has been in `PENDING_REVIEW` status for ? 2 hours, it is marked overdue and surfaced at the top of the queue with a red urgency badge. An automated WhatsApp alert is sent to the pharmacy owner.
2. **Prescription type handling:** `E_PRESCRIPTION` items (from teleconsult) have `is_verified: true` pre-filled with a doctor card from the teleconsult system. `UPLOADED` prescriptions require manual pharmacist verification before approval. The UI must visually distinguish the two types.
3. **Approval sets order line items:** The `approved_medicines` list submitted in `POST /api/v1/pharmacy/prescriptions/:rx_id/approve` becomes the definitive line items for the associated order. Prices submitted by the pharmacy override any previously quoted prices from the Rx broadcast flow.
4. **Rejection triggers notifications:** When a pharmacist rejects a prescription, the system immediately sends a WhatsApp message and an in-app push notification to the customer containing the rejection reason and any `custom_message`. The customer is prompted to upload a clearer prescription or consult a doctor.
5. **Dispense-to-billing integration:** `POST /api/v1/pharmacy/prescriptions/:rx_id/dispense-to-billing` pushes the approved medicines list as a cart to the pharmacy's POS (EPIC-006). This enables the pharmacist to complete a sale on the POS system without re-entering items.
6. **Dispense creates sale record:** Calling `dispense` transitions the prescription to `DISPENSED` status, creates a POS sale record, updates the running stock balance for Schedule H1/X medicines, and marks the associated order as ready for pickup.
7. **Plan gating:** The Rx queue is only available to pharmacies on Starter, Growth, or Pro SaaS plans. Free plan pharmacies cannot receive Rx orders through the app. Attempting to access the Rx queue on a Free plan returns `PLAN_UPGRADE_REQUIRED`.
8. **Duplicate Rx guard:** Before approval, the system checks whether the same patient has had the same drug dispensed within 30 days (using `medicines_extracted` + `customer_id`). If detected, a `POSSIBLE_DUPLICATE_RX` warning is surfaced to the pharmacist (non-blocking; pharmacist can override).

---

## API Endpoints

### 1. List Pharmacy Rx Queue

```GET /api/v1/pharmacy/prescriptions```

**Authentication:** Bearer JWT - `pharmacy_owner` | `pharmacy_staff`
**Rate Limit:** 60 req/min

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `status` | string | `PENDING` | `PENDING_REVIEW`, `APPROVED`, `DISPENSED`, `REJECTED`, `ALL` |
| `source` | string | all | `DIGITAL` (E_PRESCRIPTION) or `UPLOADED` |
| `search` | string | - | Search by patient name, doctor name, or Rx ID |
| `page` | integer | 1 | Pagination |
| `limit` | integer | 20 | Items per page (max 100) |
| `sort` | string | `urgency` | `urgency`, `received_at`, `patient_name` |

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "kpis": {
      "pending_review": 4,
      "pending_review_overdue": 1,
      "awaiting_dispense": 2,
      "dispensed_today_count": 11,
      "dispensed_today_value": 4250.00,
      "avg_turnaround_minutes": 38,
      "digital_share_pct": 62.5,
      "sla_on_time_pct": 91.3
    },
    "prescriptions": [
      {
        "rx_id": "rx_01J3KP7VXYZ123",
        "type": "UPLOADED",
        "status": "PENDING_REVIEW",
        "is_overdue": true,
        "overdue_by_minutes": 43,
        "patient_name": "Ravi Kumar",
        "doctor_name": "Dr. Priya Sharma",
        "received_at": "2026-07-24T05:10:00Z",
        "sla_deadline": "2026-07-24T07:10:00Z",
        "medicines_extracted": [
          { "name": "Metformin 500mg", "quantity": "60 tablets" }
        ],
        "order_id": "ord_01J3KP7VDEF789",
        "source": "UPLOADED"
      }
    ]
  },
  "meta": {
    "page": 1,
    "limit": 20,
    "total": 5,
    "total_pages": 1
  }
}
```

---

### 2. Get Rx Detail

```GET /api/v1/pharmacy/prescriptions/:rx_id```

**Authentication:** Bearer JWT - `pharmacy_owner` | `pharmacy_staff`
**Rate Limit:** 60 req/min

**Path Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `rx_id` | UUID | Prescription ID |

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "rx_id": "rx_01J3KP7VXYZ123",
    "type": "UPLOADED",
    "status": "PENDING_REVIEW",
    "is_overdue": true,
    "file_url": "https://s3.../signed-url",
    "patient": {
      "name": "Ravi Kumar",
      "phone": "+91-9876543210",
      "customer_id": "cust_01J3KP7VAAA111",
      "previous_orders_count": 5
    },
    "doctor": {
      "name": "Dr. Priya Sharma",
      "qualification": "MBBS MD",
      "registration_no": "MH12345",
      "verified": true
    },
    "medicines_verified": [
      {
        "name": "Metformin 500mg",
        "quantity": 60,
        "in_stock": true,
        "stock_qty": 200,
        "price": 85.00
      },
      {
        "name": "Atorvastatin 10mg",
        "quantity": 30,
        "in_stock": false,
        "stock_qty": 0,
        "price": 0
      }
    ],
    "estimated_bill_value": 85.00,
    "duplicate_rx_warning": false,
    "timeline": [
      { "event": "UPLOADED", "timestamp": "2026-07-24T05:10:00Z", "actor": "customer" },
      { "event": "RECEIVED_BY_PHARMACY", "timestamp": "2026-07-24T05:12:00Z", "actor": "system" }
    ],
    "order_id": "ord_01J3KP7VDEF789",
    "received_at": "2026-07-24T05:10:00Z",
    "sla_deadline": "2026-07-24T07:10:00Z"
  }
}
```

---

### 3. Approve Prescription

```POST /api/v1/pharmacy/prescriptions/:rx_id/approve```

**Authentication:** Bearer JWT - `pharmacy_owner` | `pharmacy_staff`
**Rate Limit:** 30 req/min

**Request Body:**
```json
{
  "approved_medicines": [
    {
      "name": "Metformin 500mg",
      "quantity": 60,
      "price": 85.00
    }
  ],
  "notes": "Atorvastatin out of stock, Metformin approved"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `approved_medicines` | array | Yes | List of approved medicines with quantity and price |
| `approved_medicines[].name` | string | Yes | Medicine name (must match dispensable names) |
| `approved_medicines[].quantity` | integer | Yes | Quantity to dispense |
| `approved_medicines[].price` | number | Yes | Price per unit (Rs) |
| `notes` | string | No | Internal notes (max 500 chars) |

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "rx_id": "rx_01J3KP7VXYZ123",
    "status": "APPROVED",
    "approved_medicines": [
      { "name": "Metformin 500mg", "quantity": 60, "price": 85.00, "line_total": 85.00 }
    ],
    "approved_by": "staff_01J3KP7VBBB222",
    "approved_at": "2026-07-24T07:55:00Z",
    "order_id": "ord_01J3KP7VDEF789"
  }
}
```

**Errors:**

| Code | HTTP | Description |
|------|------|-------------|
| `RX_NOT_FOUND` | 404 | Prescription not in this pharmacy's queue |
| `RX_ALREADY_ACTIONED` | 409 | Prescription already approved/rejected/dispensed |
| `APPROVED_MEDICINES_EMPTY` | 422 | `approved_medicines` list is empty |
| `PLAN_UPGRADE_REQUIRED` | 403 | Pharmacy is on Free plan |

---

### 4. Reject Prescription

```POST /api/v1/pharmacy/prescriptions/:rx_id/reject```

**Authentication:** Bearer JWT - `pharmacy_owner` | `pharmacy_staff`
**Rate Limit:** 30 req/min

**Request Body:**
```json
{
  "reason": "ILLEGIBLE",
  "custom_message": "The prescription image is too blurry. Please upload a clearer photo."
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `reason` | ENUM | Yes | `ILLEGIBLE`, `UNVERIFIED_PRESCRIBER`, `EXPIRED`, `NOT_STOCKED`, `INVALID` |
| `custom_message` | string | No | Human-readable message sent to customer (max 300 chars) |

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "rx_id": "rx_01J3KP7VXYZ123",
    "status": "REJECTED",
    "reason": "ILLEGIBLE",
    "custom_message": "The prescription image is too blurry. Please upload a clearer photo.",
    "rejected_by": "staff_01J3KP7VBBB222",
    "rejected_at": "2026-07-24T07:55:00Z",
    "customer_notified": true
  }
}
```

**Errors:**

| Code | HTTP | Description |
|------|------|-------------|
| `RX_NOT_FOUND` | 404 | Prescription not in this pharmacy's queue |
| `RX_ALREADY_ACTIONED` | 409 | Prescription already approved/rejected/dispensed |
| `INVALID_REJECTION_REASON` | 422 | Reason not in allowed ENUM values |

---

### 5. Mark as Dispensed

```POST /api/v1/pharmacy/prescriptions/:rx_id/dispense```

**Authentication:** Bearer JWT - `pharmacy_owner` | `pharmacy_staff`
**Rate Limit:** 30 req/min

**Request Body:**
```json
{}
```
*(No body required - dispense is triggered by the pharmacist after handing medicines to delivery.)*

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "rx_id": "rx_01J3KP7VXYZ123",
    "status": "DISPENSED",
    "dispensed_by": "staff_01J3KP7VBBB222",
    "dispensed_at": "2026-07-24T08:30:00Z",
    "sale_record_id": "sale_01J3KP7VCCC333",
    "order_status_updated_to": "READY_FOR_PICKUP"
  }
}
```

**Errors:**

| Code | HTTP | Description |
|------|------|-------------|
| `RX_NOT_FOUND` | 404 | Prescription not in this pharmacy's queue |
| `RX_NOT_APPROVED` | 409 | Cannot dispense without prior approval |

---

### 6. Send Approved Rx to POS Billing

```POST /api/v1/pharmacy/prescriptions/:rx_id/dispense-to-billing```

**Authentication:** Bearer JWT - `pharmacy_owner` | `pharmacy_staff`
**Rate Limit:** 10 req/min

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "rx_id": "rx_01J3KP7VXYZ123",
    "pos_cart_id": "pos_cart_01J3KP7VDDD444",
    "medicines_loaded": 1,
    "message": "Approved medicines pushed to POS billing cart"
  }
}
```

**Errors:**

| Code | HTTP | Description |
|------|------|-------------|
| `RX_NOT_APPROVED` | 409 | Prescription has not been approved yet |
| `POS_UNAVAILABLE` | 503 | POS integration unavailable |

---

## Data Models

### PharmacyRxQueueEntry (view over Prescription)

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `rx_id` | UUID | FK ? prescriptions.id | Prescription being reviewed |
| `pharmacy_id` | UUID | FK ? pharmacies.id | Receiving pharmacy |
| `order_id` | UUID | FK ? orders.id, nullable | Associated order |
| `received_at` | timestamp | NOT NULL | When queue entry was created |
| `sla_deadline` | timestamp | computed | `received_at + 2 hours` |
| `is_overdue` | boolean | computed | `NOW() > sla_deadline AND status = PENDING_REVIEW` |
| `status` | ENUM | NOT NULL | `PENDING_REVIEW`, `APPROVED`, `REJECTED`, `DISPENSED` |
| `approved_medicines` | JSONB | nullable | Set on approval |
| `approved_by` | UUID | FK ? users, nullable | Staff who approved |
| `approved_at` | timestamp | nullable | Approval timestamp |
| `rejected_reason` | ENUM | nullable | Rejection reason code |
| `rejected_by` | UUID | FK ? users, nullable | Staff who rejected |
| `rejected_at` | timestamp | nullable | Rejection timestamp |
| `dispensed_by` | UUID | FK ? users, nullable | Staff who dispensed |
| `dispensed_at` | timestamp | nullable | Dispense timestamp |
| `notes` | string | nullable | Pharmacist notes |
| `duplicate_warning` | boolean | default false | Set if duplicate Rx detected |

---

## Acceptance Criteria

- [ ] **Given** a pharmacy has a prescription in `PENDING_REVIEW` for 2 hours 5 minutes, **when** the Rx queue is loaded, **then** the entry is marked `is_overdue: true` and sorted to the top of the list.
- [ ] **Given** a pharmacist approves an Rx with `approved_medicines`, **when** approval succeeds, **then** the order's line items are updated to match the `approved_medicines` list and the previous cart items are replaced.
- [ ] **Given** a pharmacist rejects a prescription with reason `ILLEGIBLE`, **when** rejection is saved, **then** the customer receives a WhatsApp notification and in-app push within 30 seconds containing the rejection reason and `custom_message`.
- [ ] **Given** an e-prescription (`type: E_PRESCRIPTION`) arrives in the queue, **when** the pharmacist views it, **then** the doctor card shows `verified: true` and the doctor's NMC registration number.
- [ ] **Given** a pharmacy on the Free plan attempts to access the Rx queue, **when** the request is made, **then** the API returns HTTP 403 with `PLAN_UPGRADE_REQUIRED`.
- [ ] **Given** a pharmacist calls `dispense-to-billing`, **when** the push succeeds, **then** the POS cart is pre-filled with the approved medicines at the approved prices without any manual re-entry.
- [ ] **Given** the same patient + same drug is detected within 30 days, **when** the pharmacist views the Rx detail, **then** a `POSSIBLE_DUPLICATE_RX` warning is shown; the pharmacist can still approve.
- [ ] **Given** a pharmacist calls `dispense` on an approved prescription, **when** successful, **then** the order status transitions to `READY_FOR_PICKUP` and a POS sale record is created.

---

## Dependencies

| Dependency | Story / System | Notes |
|------------|---------------|-------|
| EPIC-008 STORY-001 - Prescription upload | Upstream | Rx queue entries originate from uploaded/e-prescriptions |
| EPIC-009 STORY-004 - e-Prescription generation | Upstream | e-Rx doctor card data feeds into queue entry |
| EPIC-010 STORY-004 - Order placement | Bidirectional | Approval updates order line items |
| EPIC-010 STORY-005 - Order lifecycle | Downstream | Dispense triggers `READY_FOR_PICKUP` status |
| EPIC-006 - POS/billing | Downstream | `dispense-to-billing` pushes to POS cart |
| Notification service (WhatsApp + Push) | Platform | Rejection and overdue alerts |
| EPIC-008 STORY-004 - Drug register | Downstream | Dispense of H1/X drugs writes to register |

---

## Notes

- Queue KPI stats (`dispensed_today_value`, `avg_turnaround_minutes`, etc.) should be computed server-side and cached with a 60-second TTL to avoid expensive realtime aggregation on every page load.
- `digital_share_pct` = `(e_prescription_count / total_rx_count) - 100` for the rolling 30-day window.
- The `sla_on_time_pct` KPI is a rolling 7-day average of prescriptions reviewed within the 2-hour SLA window.
- `approved_medicines[].price` is the final dispensed price and overrides any POS listed price for this transaction.
