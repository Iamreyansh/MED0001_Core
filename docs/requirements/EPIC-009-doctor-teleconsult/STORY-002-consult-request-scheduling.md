# STORY-002: Patient Consultation Request and Scheduling

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-002 |
| **Epic** | EPIC-009 - Doctor Teleconsult |
| **Priority** | P0 |
| **Complexity** | M |
| **Status** | Draft |

---

## Overview

This story covers the patient-facing side of teleconsult - how a customer requests a consultation, specifies their symptoms and medicines needed, receives an assigned doctor, and can cancel before the call begins. Consultations are 100% free for patients. A consult can be requested for immediate connection (`slot: NOW`) or scheduled for a future datetime. Cart-mode consults - triggered when a patient needs an Rx for items already in their cart - automatically link the resulting e-prescription back to the cart on completion. The story also manages the patient's consult history and active consult tracking across concurrent orders.

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| `customer` | Full access | Request, view, cancel, list own consults, rate |
| `admin_super` | Read-only | View consult requests via admin endpoint |
| `admin_operations` | Read-only | View and manage queue via admin endpoint |

---

## Business Rules

1. **Free for patients, always:** The consult creation endpoint does not require any payment information. No charge is created on the customer's account for a teleconsult.
2. **NOW slot assignment:** When `slot: NOW` is requested, the system immediately selects the available doctor with the oldest `last_assigned_at` (load-balanced). If no doctors are available, the patient is queued and the response includes `queue_position` and `estimated_wait_minutes`.
3. **Scheduled slot storage:** When `slot` is a specific datetime (ISO 8601), the consult is created with status `REQUESTED` and stored for the scheduled time. No doctor is pre-assigned; assignment happens when the slot time arrives and a doctor is triggered to review.
4. **Maximum active consult limit:** A customer can have at most 3 non-completed consult requests (`status` not in `COMPLETED`, `CANCELLED`) at any time. Attempting to create a 4th returns `MAX_ACTIVE_CONSULTS_REACHED`.
5. **Auto-cancellation for unstarted consults:** If a scheduled consult is not started (status remains `REQUESTED` or `DOCTOR_REVIEWING`) within 30 minutes past the scheduled time, the system auto-cancels it and notifies the patient via push notification.
6. **Cart-mode consult linkage:** When `cart_id` is provided, the consult is flagged as `is_cart_mode: true`. Once the doctor issues an e-prescription, the e-Rx is automatically linked to the specified cart. Only one active cart-mode consult is allowed per cart.
7. **Cancellation window:** A customer can cancel a consult only when its status is `REQUESTED` or `DOCTOR_REVIEWING`. Once status reaches `CALLING` or `IN_CALL`, cancellation is blocked from the customer side (admin can still cancel).
8. **Patient phone for doctor callback:** The `patient_phone` field is used by the assigned doctor to initiate the call. It defaults to the customer's registered phone but can be overridden in the request (e.g., "call this other number").

---

## API Endpoints

### 1. Request a Consultation

```POST /api/v1/consults/request```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 5 req/min per customer

**Request Body:**
```json
{
  "patient_name": "Ravi Kumar",
  "patient_phone": "+91-9876543210",
  "slot": "NOW",
  "symptoms": ["fatigue", "increased thirst", "frequent urination"],
  "medicines_needing_rx": [
    { "name": "Metformin 500mg", "reason": "REFILL" },
    { "name": "Glipizide 5mg", "reason": "NEW_SYMPTOMS" }
  ],
  "cart_id": "cart_01J3KP7VGHJ000",
  "reason": "RX_NEEDED"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `patient_name` | string | Yes | Patient name for doctor's reference |
| `patient_phone` | string | Yes | Phone number doctor will call |
| `slot` | string | Yes | `"NOW"` or ISO 8601 datetime for scheduled slot |
| `symptoms` | string[] | No | Symptom tags (free text, max 10) |
| `medicines_needing_rx` | array | No | Medicines for which Rx is needed |
| `medicines_needing_rx[].name` | string | Yes (if array present) | Medicine name |
| `medicines_needing_rx[].reason` | ENUM | Yes (if array present) | `REFILL`, `NEW_SYMPTOMS`, `DOCTOR_ADVISED` |
| `cart_id` | UUID | No | Link this consult to an active cart |
| `reason` | ENUM | Yes | `GENERAL`, `RX_NEEDED` |

**Response `201 Created`:**
```json
{
  "success": true,
  "data": {
    "consult_id": "consult_01J3KP7VKKK111",
    "status": "DOCTOR_REVIEWING",
    "doctor": {
      "id": "tdoc_01J3KP7VXYZ123",
      "name": "Dr. Anil Mehta",
      "qualification": "MBBS MD",
      "avatar_url": "https://cdn.nammamedmate.com/doctors/anil-mehta.jpg",
      "registration_no": "DL98765",
      "rating": 4.7
    },
    "scheduled_at": "2026-07-24T10:30:00Z",
    "estimated_call_in_minutes": 3,
    "cart_id": "cart_01J3KP7VGHJ000",
    "is_cart_mode": true,
    "created_at": "2026-07-24T10:30:00Z"
  }
}
```

**Response when no doctors available (queued):**
```json
{
  "success": true,
  "data": {
    "consult_id": "consult_01J3KP7VKKK111",
    "status": "REQUESTED",
    "doctor": null,
    "queue_position": 3,
    "estimated_wait_minutes": 12,
    "scheduled_at": null,
    "cart_id": "cart_01J3KP7VGHJ000",
    "is_cart_mode": true,
    "created_at": "2026-07-24T10:30:00Z"
  }
}
```

**Errors:**

| Code | HTTP | Description |
|------|------|-------------|
| `MAX_ACTIVE_CONSULTS_REACHED` | 429 | Customer already has 3 active consults |
| `CART_NOT_FOUND` | 404 | Provided cart_id not found or not active |
| `CART_ALREADY_HAS_CONSULT` | 409 | Cart already linked to another active consult |

---

### 2. Get Consult Status

```GET /api/v1/consults/:consult_id```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 60 req/min

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "consult_id": "consult_01J3KP7VKKK111",
    "doctor": {
      "id": "tdoc_01J3KP7VXYZ123",
      "name": "Dr. Anil Mehta",
      "qualification": "MBBS MD",
      "avatar_url": "https://cdn.nammamedmate.com/doctors/anil-mehta.jpg",
      "registration_no": "DL98765",
      "rating": 4.7
    },
    "status": "IN_CALL",
    "scheduled_at": "2026-07-24T10:30:00Z",
    "call_started_at": "2026-07-24T10:33:00Z",
    "call_ended_at": null,
    "e_prescription_id": null,
    "cart_id": "cart_01J3KP7VGHJ000",
    "is_cart_mode": true,
    "patient_name": "Ravi Kumar",
    "created_at": "2026-07-24T10:30:00Z"
  }
}
```

---

### 3. Cancel Consult

```POST /api/v1/consults/:consult_id/cancel```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 10 req/min

**Request Body:**
```json
{
  "reason": "No longer needed"
}
```

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "consult_id": "consult_01J3KP7VKKK111",
    "status": "CANCELLED",
    "cancelled_at": "2026-07-24T10:32:00Z"
  }
}
```

**Errors:**

| Code | HTTP | Description |
|------|------|-------------|
| `CONSULT_CANNOT_CANCEL` | 409 | Status is CALLING, IN_CALL, COMPLETED, or already CANCELLED |
| `CONSULT_NOT_FOUND` | 404 | Consult not found for this customer |

---

### 4. List Customer Consults

```GET /api/v1/consults```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 30 req/min

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `status` | string | `ALL` | `REQUESTED`, `IN_CALL`, `COMPLETED`, `CANCELLED`, `ALL` |
| `page` | integer | 1 | Pagination |
| `limit` | integer | 20 | Items per page |

**Response `200 OK`:**
```json
{
  "success": true,
  "data": [
    {
      "consult_id": "consult_01J3KP7VKKK111",
      "date": "2026-07-24",
      "doctor_name": "Dr. Anil Mehta",
      "status": "COMPLETED",
      "e_prescription_id": "erx_01J3KP7VLLL222",
      "cart_id": null,
      "is_cart_mode": false,
      "rating_given": 5,
      "created_at": "2026-07-24T10:30:00Z"
    }
  ],
  "meta": {
    "page": 1,
    "limit": 20,
    "total": 1,
    "total_pages": 1
  }
}
```

---

## Data Models

### Consult

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID v4 | PK, NOT NULL | Consult identifier |
| `customer_id` | UUID | FK ? customers.id, NOT NULL | Patient |
| `doctor_id` | UUID | FK ? teleconsult_doctors.id, nullable | Assigned doctor |
| `patient_name` | string | NOT NULL | Name for doctor's reference |
| `patient_phone` | string | NOT NULL | Doctor calls this number |
| `slot_type` | ENUM | NOT NULL | `NOW`, `SCHEDULED` |
| `scheduled_at` | timestamp | nullable | For SCHEDULED slots |
| `symptoms` | string[] | nullable | Patient-reported symptoms |
| `medicines_needing_rx` | JSONB | nullable | Medicines needing prescription |
| `cart_id` | UUID | FK ? carts.id, nullable | Linked cart (cart-mode) |
| `is_cart_mode` | boolean | default false | Whether this is a cart-triggered consult |
| `reason` | ENUM | NOT NULL | `GENERAL`, `RX_NEEDED` |
| `status` | ENUM | NOT NULL, default `REQUESTED` | `REQUESTED`, `DOCTOR_REVIEWING`, `CALLING`, `IN_CALL`, `COMPLETED`, `CANCELLED` |
| `call_started_at` | timestamp | nullable | When doctor initiated the call |
| `call_ended_at` | timestamp | nullable | When call was ended |
| `e_prescription_id` | UUID | FK ? prescriptions.id, nullable | Issued e-Rx (if any) |
| `is_advice_only` | boolean | default false | True if no medicines in e-Rx |
| `rating` | integer | nullable, 1-5 | Patient's post-consult rating |
| `feedback_text` | string | nullable, max 500 | Patient's written feedback |
| `auto_cancelled_reason` | string | nullable | Set on system auto-cancellation |
| `created_at` | timestamp | NOT NULL | Request creation time |
| `updated_at` | timestamp | NOT NULL | Last status change |

---

## Acceptance Criteria

- [ ] **Given** a customer requests a consult with `slot: NOW` and a doctor is available, **when** the request is created, **then** the response includes the assigned doctor's card and `estimated_call_in_minutes`.
- [ ] **Given** no doctors are available when a NOW consult is requested, **when** the request is created, **then** the response includes `queue_position` and `estimated_wait_minutes`, and `doctor` is null.
- [ ] **Given** a customer already has 3 active consults, **when** they attempt a 4th consult request, **then** the API returns HTTP 429 with `MAX_ACTIVE_CONSULTS_REACHED`.
- [ ] **Given** a customer attempts to cancel a consult in `IN_CALL` status, **when** the cancel request is made, **then** the API returns HTTP 409 with `CONSULT_CANNOT_CANCEL`.
- [ ] **Given** a cart-mode consult is created with `cart_id`, **when** the doctor issues an e-prescription, **then** the e-Rx is automatically linked to the cart and `cart.prescription_id` is updated.
- [ ] **Given** a scheduled consult is not started within 30 minutes of its scheduled time, **when** the auto-cancellation job runs, **then** the consult transitions to `CANCELLED` and the customer receives a push notification.
- [ ] **Given** a customer requests a consult for `cart_id` that already has an active cart-mode consult, **when** the request is made, **then** the API returns HTTP 409 with `CART_ALREADY_HAS_CONSULT`.
- [ ] **Given** a customer views `GET /api/v1/consults/:consult_id` for a consult belonging to a different customer, **then** the API returns HTTP 404.

---

## Dependencies

| Dependency | Story / System | Notes |
|------------|---------------|-------|
| EPIC-009 STORY-001 - Doctor profile management | Upstream | `is_available` and `last_assigned_at` used for assignment |
| EPIC-009 STORY-003 - Session management | Downstream | Status transitions driven by session management |
| EPIC-009 STORY-004 - e-Prescription generation | Downstream | e-Rx ID written back to consult on issuance |
| EPIC-010 STORY-001 - Cart management | Bidirectional | `cart_id` linkage and prescription attachment |
| Notification service (Push) | Platform | Queue wait updates, auto-cancel notifications |

---

## Notes

- `estimated_call_in_minutes` for queued consults is calculated as `queue_position - avg_call_duration_minutes` (using the rolling 7-day average call duration).
- `patient_phone` stored on the consult is never returned in the customer-facing `GET /api/v1/consults` list (privacy). It is only used internally by the calling service.
- For NOW slots where a doctor is available, the `status` transitions from `REQUESTED` ? `DOCTOR_REVIEWING` immediately on creation (the system performs the assignment synchronously in the POST handler).
