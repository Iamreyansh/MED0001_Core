# STORY-003: Prescription Quote Broadcast to Pharmacies

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-003 |
| **Epic** | EPIC-010 - Order Management |
| **Priority** | P0 |
| **Complexity** | L |
| **Status** | Draft |

---

## Overview

This story defines the Rx quote broadcast flow - the mechanism by which a patient with a prescription (uploaded or e-Rx) can send it to nearby pharmacies and receive competitive quotes before confirming an order. The broadcast is sent to up to 10 pharmacies within a 3km radius. Pharmacies have 15 minutes to respond with a quote (list of available medicines and their prices and ETA). The patient sees incoming quotes with FASTEST and LOWEST PRICE tags, selects the best quote, and a cart is pre-filled with the quoted medicines. The broadcast auto-expires after 30 minutes if no viable quote is selected.

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| `customer` | Full access | Initiate broadcast, view quotes, select quote |
| `pharmacy_owner` | Quote/Decline | View incoming broadcasts, submit or decline quotes |
| `pharmacy_staff` | Quote/Decline | Same as pharmacy_owner |
| `admin_operations` | Read-only | Monitor broadcasts for operational oversight |

---

## Business Rules

1. **Broadcast radius and pharmacy cap:** The broadcast is sent to pharmacies within 3km of the delivery address. A maximum of 10 pharmacies are notified per broadcast, selected by proximity (nearest first).
2. **Pharmacy response window:** Each pharmacy has exactly 15 minutes from `received_at` to submit a quote. After 15 minutes, the pharmacy's slot expires and shows `EXPIRED` on the customer's quote list. The customer can still see quotes from pharmacies that responded within their window.
3. **Quote expiry:** An individual quote expires 20 minutes after it was submitted (`quoted_at + 20 minutes`). Expired quotes cannot be selected.
4. **Quote view threshold:** The "View quotes" button (or equivalent UI unlock) appears in the customer app when: (a) ? 2 quotes have been received OR (b) ? 1 quote has been received and 5 minutes have elapsed since broadcast. The API returns `can_view_quotes: true` when this condition is met.
5. **Quote tags:** `FASTEST` = the quote with the lowest `delivery_eta_minutes` (ties broken by price). `LOWEST PRICE` = the quote with the lowest `total_payable` (ties broken by ETA). A single quote can hold both tags.
6. **Selecting a quote creates a cart:** `POST /api/v1/orders/rx-quote/:broadcast_id/select` creates a new cart pre-filled with the quoted medicines at the quoted prices, locked to the selected pharmacy, and with the broadcast's prescription attached.
7. **Broadcast auto-expiry:** If 30 minutes elapse since `broadcast_at` and no quote has been selected, the broadcast transitions to `EXPIRED` status and the customer is notified via push. They can re-broadcast or choose a different approach.
8. **Prescription privacy:** The prescription file is NOT transmitted during the broadcast. Only a redacted summary (medicines list from OCR extraction or e-Rx) is visible to pharmacies during the quote phase. The full prescription file is shared with the selected pharmacy only after quote selection confirms the order.

---

## API Endpoints

### 1. Broadcast Prescription to Pharmacies

```POST /api/v1/orders/rx-quote/broadcast```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 5 req/min

**Request Body:**
```json
{
  "prescription_id": "rx_01J3KP7VXYZ123",
  "delivery_address_id": "addr_01J3KP7VPPP666",
  "patient_name": "Ravi Kumar",
  "notes": "Please quote for all medicines if possible"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `prescription_id` | UUID | Yes | Prescription to broadcast |
| `delivery_address_id` | UUID | Yes | Delivery address (determines broadcast radius) |
| `patient_name` | string | Yes | Patient name for pharmacies |
| `notes` | string | No | Optional notes to pharmacies (max 300 chars) |

**Response `201 Created`:**
```json
{
  "success": true,
  "data": {
    "broadcast_id": "bc_01J3KP7VRRR888",
    "status": "ACTIVE",
    "pharmacies_notified": 7,
    "broadcast_at": "2026-07-24T11:00:00Z",
    "expires_at": "2026-07-24T11:30:00Z",
    "pharmacies": [
      {
        "pharmacy_id": "ph_01J3KP7VFFF666",
        "name": "Sai Medicals",
        "area": "Koramangala",
        "distance_km": 1.2,
        "status": "NOTIFIED"
      }
    ],
    "can_view_quotes": false,
    "quotes_received": 0
  }
}
```

**Errors:**

| Code | HTTP | Description |
|------|------|-------------|
| `PRESCRIPTION_NOT_FOUND` | 404 | Prescription not found for this customer |
| `PRESCRIPTION_EXPIRED` | 422 | Prescription has expired |
| `ADDRESS_NOT_FOUND` | 404 | Delivery address not in customer's address book |
| `NO_PHARMACIES_NEARBY` | 422 | No eligible pharmacies within 3km |

---

### 2. Get Broadcast Status

```GET /api/v1/orders/rx-quote/:broadcast_id```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 60 req/min (designed for polling)

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "broadcast_id": "bc_01J3KP7VRRR888",
    "status": "ACTIVE",
    "broadcast_at": "2026-07-24T11:00:00Z",
    "expires_at": "2026-07-24T11:30:00Z",
    "pharmacies_notified": 7,
    "quotes_received": 3,
    "can_view_quotes": true,
    "pharmacies": [
      {
        "pharmacy_id": "ph_01J3KP7VFFF666",
        "name": "Sai Medicals",
        "area": "Koramangala",
        "distance_km": 1.2,
        "status": "QUOTED",
        "quote": {
          "medicines_covered": 2,
          "total_payable": 340.50,
          "eta_minutes": 22,
          "expires_at": "2026-07-24T11:25:00Z"
        }
      },
      {
        "pharmacy_id": "ph_01J3KP7VQQQ777",
        "name": "Apollo Pharmacy",
        "area": "BTM Layout",
        "distance_km": 2.1,
        "status": "OUT_OF_STOCK",
        "quote": null
      }
    ]
  }
}
```

---

### 3. List All Received Quotes

```GET /api/v1/orders/rx-quote/:broadcast_id/quotes```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 30 req/min

**Response `200 OK`:**
```json
{
  "success": true,
  "data": [
    {
      "pharmacy_id": "ph_01J3KP7VFFF666",
      "pharmacy_name": "Sai Medicals",
      "pharmacy_logo": "https://cdn.nammamedmate.com/pharmacies/sai-medicals.png",
      "rating": 4.6,
      "distance_km": 1.2,
      "eta_minutes": 22,
      "tags": ["LOWEST_PRICE"],
      "medicines_covered": 2,
      "medicines_total_requested": 2,
      "medicines": [
        { "name": "Metformin 500mg", "quantity": 60, "price": 255.00 },
        { "name": "Glipizide 5mg", "quantity": 30, "price": 85.50 }
      ],
      "total_payable": 340.50,
      "delivery_fee": 25.00,
      "handling_fee": 5.00,
      "grand_total": 370.50,
      "quoted_at": "2026-07-24T11:08:00Z",
      "expires_at": "2026-07-24T11:28:00Z",
      "is_expired": false
    },
    {
      "pharmacy_id": "ph_01J3KP7VSSS999",
      "pharmacy_name": "MedPlus",
      "pharmacy_logo": "https://cdn.nammamedmate.com/pharmacies/medplus.png",
      "rating": 4.2,
      "distance_km": 1.8,
      "eta_minutes": 18,
      "tags": ["FASTEST"],
      "medicines_covered": 1,
      "medicines_total_requested": 2,
      "medicines": [
        { "name": "Metformin 500mg", "quantity": 60, "price": 270.00 }
      ],
      "total_payable": 270.00,
      "delivery_fee": 0.00,
      "handling_fee": 5.00,
      "grand_total": 275.00,
      "quoted_at": "2026-07-24T11:05:00Z",
      "expires_at": "2026-07-24T11:25:00Z",
      "is_expired": false
    }
  ]
}
```

---

### 4. Select a Quote

```POST /api/v1/orders/rx-quote/:broadcast_id/select```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 5 req/min

**Request Body:**
```json
{ "pharmacy_id": "ph_01J3KP7VFFF666" }
```

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "broadcast_id": "bc_01J3KP7VRRR888",
    "status": "SELECTED",
    "cart_id": "cart_01J3KP7VTTT000",
    "cart": {
      "pharmacy": { "id": "ph_01J3KP7VFFF666", "name": "Sai Medicals" },
      "items": [
        { "name": "Metformin 500mg", "quantity": 60, "price": 255.00 },
        { "name": "Glipizide 5mg", "quantity": 30, "price": 85.50 }
      ],
      "prescription_id": "rx_01J3KP7VXYZ123",
      "bill": {
        "item_total": 340.50,
        "delivery_fee": 25.00,
        "handling_fee": 5.00,
        "total_payable": 370.50
      }
    }
  }
}
```

**Errors:**

| Code | HTTP | Description |
|------|------|-------------|
| `BROADCAST_NOT_FOUND` | 404 | Broadcast ID not found for this customer |
| `BROADCAST_EXPIRED` | 422 | Broadcast has expired |
| `QUOTE_NOT_FOUND` | 404 | No quote from the specified pharmacy |
| `QUOTE_EXPIRED` | 422 | The pharmacy's quote has expired |

---

### 5. Pharmacy - List Incoming Broadcasts

```GET /api/v1/pharmacy/rx-quotes```

**Authentication:** Bearer JWT - `pharmacy_owner` | `pharmacy_staff`
**Rate Limit:** 30 req/min

**Response `200 OK`:**
```json
{
  "success": true,
  "data": [
    {
      "broadcast_id": "bc_01J3KP7VRRR888",
      "patient_name": "Ravi Kumar",
      "distance_km": 1.2,
      "medicines_requested": ["Metformin 500mg (60)", "Glipizide 5mg (30)"],
      "received_at": "2026-07-24T11:00:00Z",
      "response_deadline": "2026-07-24T11:15:00Z",
      "time_remaining_seconds": 420,
      "status": "PENDING_RESPONSE"
    }
  ]
}
```

---

### 6. Pharmacy - Submit Quote

```POST /api/v1/pharmacy/rx-quotes/:broadcast_id/quote```

**Authentication:** Bearer JWT - `pharmacy_owner` | `pharmacy_staff`
**Rate Limit:** 10 req/min

**Request Body:**
```json
{
  "medicines_available": [
    { "name": "Metformin 500mg", "qty": 60, "price": 255.00 },
    { "name": "Glipizide 5mg", "qty": 30, "price": 85.50 }
  ],
  "delivery_eta_minutes": 22
}
```

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "broadcast_id": "bc_01J3KP7VRRR888",
    "status": "QUOTED",
    "quote_expires_at": "2026-07-24T11:28:00Z"
  }
}
```

---

### 7. Pharmacy - Decline Quote

```POST /api/v1/pharmacy/rx-quotes/:broadcast_id/decline```

**Authentication:** Bearer JWT - `pharmacy_owner` | `pharmacy_staff`
**Rate Limit:** 10 req/min

**Request Body:**
```json
{ "reason": "OUT_OF_STOCK" }
```

**Response `200 OK`:**
```json
{
  "success": true,
  "data": {
    "broadcast_id": "bc_01J3KP7VRRR888",
    "status": "OUT_OF_STOCK"
  }
}
```

---

## Data Models

### RxBroadcast

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID v4 | PK, NOT NULL | Broadcast identifier |
| `customer_id` | UUID | FK ? customers.id, NOT NULL | Broadcasting customer |
| `prescription_id` | UUID | FK ? prescriptions.id, NOT NULL | Broadcast prescription |
| `delivery_address_id` | UUID | FK ? addresses.id, NOT NULL | Delivery location |
| `patient_name` | string | NOT NULL | Patient name |
| `notes` | string | nullable | Customer notes |
| `status` | ENUM | NOT NULL, default `ACTIVE` | `ACTIVE`, `SELECTED`, `EXPIRED` |
| `pharmacies_notified` | integer | NOT NULL | Count of notified pharmacies |
| `broadcast_at` | timestamp | NOT NULL | When broadcast was sent |
| `expires_at` | timestamp | NOT NULL | `broadcast_at + 30 min` |
| `selected_pharmacy_id` | UUID | FK ? pharmacies.id, nullable | Set when customer selects |
| `resulting_cart_id` | UUID | FK ? carts.id, nullable | Cart created on selection |
| `created_at` | timestamp | NOT NULL | Creation time |

### RxBroadcastPharmacy

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID v4 | PK | Entry identifier |
| `broadcast_id` | UUID | FK ? rx_broadcasts.id, NOT NULL | Parent broadcast |
| `pharmacy_id` | UUID | FK ? pharmacies.id, NOT NULL | Notified pharmacy |
| `distance_km` | decimal | NOT NULL | Distance at notification time |
| `status` | ENUM | NOT NULL | `NOTIFIED`, `REVIEWING`, `QUOTED`, `OUT_OF_STOCK`, `EXPIRED` |
| `medicines_available` | JSONB | nullable | Quoted medicines list |
| `delivery_eta_minutes` | integer | nullable | Quoted ETA |
| `total_payable` | decimal | nullable | Computed quote total |
| `received_at` | timestamp | NOT NULL | When pharmacy was notified |
| `response_deadline` | timestamp | NOT NULL | `received_at + 15 min` |
| `quoted_at` | timestamp | nullable | When quote was submitted |
| `quote_expires_at` | timestamp | nullable | `quoted_at + 20 min` |
| `tags` | string[] | nullable | `FASTEST`, `LOWEST_PRICE` |

---

## Acceptance Criteria

- [ ] **Given** a broadcast is initiated with 7 eligible pharmacies within 3km, **when** the broadcast is created, **then** all 7 pharmacies receive a notification and the response includes `pharmacies_notified: 7`.
- [ ] **Given** there are 12 eligible pharmacies within 3km, **when** the broadcast is created, **then** only the 10 nearest pharmacies are notified.
- [ ] **Given** a pharmacy has not responded within 15 minutes of `received_at`, **when** the expiry job runs, **then** their status updates to `EXPIRED`.
- [ ] **Given** 0 quotes have been received and only 3 minutes have elapsed, **when** the broadcast status is polled, **then** `can_view_quotes: false`.
- [ ] **Given** 1 quote is received and 6 minutes have elapsed since broadcast, **when** the status is polled, **then** `can_view_quotes: true`.
- [ ] **Given** two quotes are received where pharmacy A has lower price but pharmacy B has lower ETA, **when** quotes are listed, **then** A has tag `LOWEST_PRICE` and B has tag `FASTEST`.
- [ ] **Given** a customer selects a quote, **when** selection succeeds, **then** a new cart is created pre-filled with the quoted medicines at the quoted prices, locked to the selected pharmacy, with the prescription attached.
- [ ] **Given** 30 minutes elapse with no selection, **when** the auto-expiry job runs, **then** broadcast status transitions to `EXPIRED` and the customer receives a push notification.

---

## Dependencies

| Dependency | Story / System | Notes |
|------------|---------------|-------|
| EPIC-008 STORY-001 - Prescription | Upstream | `prescription_id` must be valid and non-expired |
| EPIC-010 STORY-001 - Cart management | Downstream | Selecting a quote creates a new cart |
| EPIC-010 STORY-002 - Pharmacy selection | Upstream | Geo-radius query for eligible pharmacies |
| Notification service (Push + WhatsApp) | Platform | Pharmacy notifications on broadcast, customer quote ready |

---

## Notes

- The broadcast uses the medicines list from `medicines_extracted` (OCR) or `medicines` (e-Rx) on the prescription - not the raw file. The raw prescription file is transmitted to the selected pharmacy only after quote selection.
- `total_payable` on a quote is `sum(medicine prices) + handling_fee (Rs 5) + delivery_fee (Rs 25 or Rs 0)`. Coupons are NOT applied at the quote stage.
- Quote selection atomically: creates cart, locks broadcast, marks other quotes as non-selectable.
