# STORY-002: Delivery Address Management

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-002 |
| **Epic** | EPIC-002 - Customer Management |
| **Priority** | P0 |
| **Complexity** | M |
| **Status** | Draft |

---

## Overview

This story provides customers with a reusable address book for delivery addresses, including geocode-based address creation, default address management, and deletion with order-safety guards. Each address captures structured fields (flat/building, area, city, state, pincode) as well as a latitude/longitude pair critical for delivery zone matching and ETA calculation. A geocode utility endpoint lets the app convert a map pin-drop or GPS coordinate into a pre-filled address form. Addresses tied to active orders are protected from deletion.

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| customer | Admin | Full CRUD over own addresses |
| admin_support | Read | Can view a customer's saved addresses for support purposes |
| admin_operations | Read | Can view addresses for logistics investigations |

## Business Rules

1. A customer can save a maximum of 10 addresses. Attempting to create an 11th address returns `422 ADDRESS_LIMIT_REACHED`.
2. Every address must have both `latitude` and `longitude`. If the customer provides only pincode, the app must call the geocode endpoint first. Coordinates are required for delivery zone matching at checkout.
3. Each address has a `label` which must be one of: `HOME`, `WORK`, `OTHER`. Multiple addresses with the same label are allowed.
4. The `pincode` must be a valid 6-digit Indian postal code (numeric, starting with a digit 1-9). Server validates format only; availability of delivery to that pincode is checked at checkout (EPIC-003), not here.
5. When a customer's first address is created, it is automatically set as the default (`is_default: true`). If the customer already has a default address and creates a new one, the new one is NOT automatically set as default unless the request includes `"is_default": true`.
6. There can only be one default address at any time. Setting a new default via `PATCH /:id/set-default` automatically unsets `is_default` on the previously default address in the same atomic transaction.
7. An address cannot be deleted if it is currently the delivery address for any order with status `PENDING`, `CONFIRMED`, `PACKED`, or `OUT_FOR_DELIVERY`. Attempting to do so returns `409 ADDRESS_IN_ACTIVE_ORDER`.
8. If a customer deletes their only saved address, `default_address_id` on the customer record is set to NULL. The address can be deleted as long as no active order references it.
9. The geocode endpoint (`POST /addresses/geocode`) calls Google Maps Geocoding API internally. It accepts `latitude` and `longitude` and returns suggested `area_locality`, `city`, `state`, and `pincode`. The full address is assembled by the customer in the app; coordinates are stored as-is from the geocode request.

## API Endpoints

### 1. List Saved Addresses

```
GET /api/v1/customers/me/addresses
```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 30 req/min per user

**Success Response - `200 OK`:**
```json
{
  "success": true,
  "data": [
    {
      "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
      "label": "HOME",
      "flat_building": "Flat 4B, Prestige Shantiniketan",
      "area_locality": "Whitefield",
      "city": "Bengaluru",
      "state": "Karnataka",
      "pincode": "560066",
      "latitude": 12.9693,
      "longitude": 77.7499,
      "is_default": true,
      "created_at": "2026-01-15T08:00:00Z",
      "updated_at": "2026-01-15T08:00:00Z"
    },
    {
      "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "label": "WORK",
      "flat_building": "3rd Floor, Salarpuria Softzone",
      "area_locality": "Bellandur",
      "city": "Bengaluru",
      "state": "Karnataka",
      "pincode": "560103",
      "latitude": 12.9256,
      "longitude": 77.6791,
      "is_default": false,
      "created_at": "2026-02-10T10:00:00Z",
      "updated_at": "2026-02-10T10:00:00Z"
    }
  ],
  "meta": {}
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 401 | `UNAUTHORIZED` | Token missing or invalid |

---

### 2. Create Address

```
POST /api/v1/customers/me/addresses
```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 20 req/min per user

**Request Body (`application/json`):**
```json
{
  "label": "string - required, enum: HOME|WORK|OTHER",
  "flat_building": "string - required, max:200, flat number, building name or house number",
  "area_locality": "string - required, max:200, area, locality or street name",
  "city": "string - required, max:100",
  "state": "string - required, max:100",
  "pincode": "string - required, exactly 6 digits, valid Indian postal code",
  "latitude": "number - required, range: -90 to 90, decimal degrees",
  "longitude": "number - required, range: -180 to 180, decimal degrees",
  "is_default": "boolean - optional, default false"
}
```

**Success Response - `201 Created`:**
```json
{
  "success": true,
  "data": {
    "id": "c2d3e4f5-a6b7-8901-bcde-f12345678901",
    "label": "HOME",
    "flat_building": "Flat 4B, Prestige Shantiniketan",
    "area_locality": "Whitefield",
    "city": "Bengaluru",
    "state": "Karnataka",
    "pincode": "560066",
    "latitude": 12.9693,
    "longitude": 77.7499,
    "is_default": true,
    "created_at": "2026-07-24T02:00:00Z",
    "updated_at": "2026-07-24T02:00:00Z"
  },
  "meta": {}
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `VALIDATION_ERROR` | Missing required fields, invalid pincode, coordinates out of range, invalid label |
| 401 | `UNAUTHORIZED` | Token missing or invalid |
| 422 | `ADDRESS_LIMIT_REACHED` | Customer already has 10 saved addresses |

---

### 3. Update Address

```
PUT /api/v1/customers/me/addresses/:id
```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 20 req/min per user

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| :id | UUID | Address ID to update |

**Request Body (`application/json`):**
```json
{
  "label": "string - required, enum: HOME|WORK|OTHER",
  "flat_building": "string - required, max:200",
  "area_locality": "string - required, max:200",
  "city": "string - required, max:100",
  "state": "string - required, max:100",
  "pincode": "string - required, exactly 6 digits",
  "latitude": "number - required, -90 to 90",
  "longitude": "number - required, -180 to 180"
}
```

**Success Response - `200 OK`:**
```json
{
  "success": true,
  "data": {
    "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "label": "HOME",
    "flat_building": "Flat 4B, Prestige Shantiniketan Tower 2",
    "area_locality": "Whitefield",
    "city": "Bengaluru",
    "state": "Karnataka",
    "pincode": "560066",
    "latitude": 12.9693,
    "longitude": 77.7499,
    "is_default": true,
    "updated_at": "2026-07-24T02:30:00Z"
  },
  "meta": {}
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `VALIDATION_ERROR` | Missing or invalid fields |
| 401 | `UNAUTHORIZED` | Token missing or invalid |
| 404 | `ADDRESS_NOT_FOUND` | Address not found or does not belong to this customer |

---

### 4. Delete Address

```
DELETE /api/v1/customers/me/addresses/:id
```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 20 req/min per user

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| :id | UUID | Address ID to delete |

**Success Response - `200 OK`:**
```json
{
  "success": true,
  "data": {
    "message": "Address deleted successfully."
  },
  "meta": {}
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 401 | `UNAUTHORIZED` | Token missing or invalid |
| 404 | `ADDRESS_NOT_FOUND` | Address not found or does not belong to this customer |
| 409 | `ADDRESS_IN_ACTIVE_ORDER` | Address is used by an order in active status |

---

### 5. Set Default Address

```
PATCH /api/v1/customers/me/addresses/:id/set-default
```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 20 req/min per user

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| :id | UUID | Address ID to set as default |

**Success Response - `200 OK`:**
```json
{
  "success": true,
  "data": {
    "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "is_default": true,
    "previous_default_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "message": "Default address updated."
  },
  "meta": {}
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 401 | `UNAUTHORIZED` | Token missing or invalid |
| 404 | `ADDRESS_NOT_FOUND` | Address not found or does not belong to this customer |
| 409 | `ALREADY_DEFAULT` | This address is already the default |

---

### 6. Geocode Coordinates to Address

```
POST /api/v1/customers/me/addresses/geocode
```

**Authentication:** Bearer JWT - `customer`
**Rate Limit:** 10 req/min per user

**Request Body (`application/json`):**
```json
{
  "latitude": "number - required, -90 to 90",
  "longitude": "number - required, -180 to 180"
}
```

**Success Response - `200 OK`:**
```json
{
  "success": true,
  "data": {
    "suggested_address": {
      "flat_building": "",
      "area_locality": "Whitefield",
      "city": "Bengaluru",
      "state": "Karnataka",
      "pincode": "560066",
      "formatted_address": "Whitefield, Bengaluru, Karnataka 560066, India",
      "latitude": 12.9693,
      "longitude": 77.7499
    }
  },
  "meta": {}
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `VALIDATION_ERROR` | Coordinates missing or out of range |
| 401 | `UNAUTHORIZED` | Token missing or invalid |
| 502 | `GEOCODE_SERVICE_ERROR` | Google Maps API returned an error |

---

## Data Models

### CustomerAddress

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | UUID | PK, auto-gen | Primary key |
| customer_id | UUID | FK ? customers.id, NOT NULL, indexed | Owning customer |
| label | VARCHAR(10) | NOT NULL | HOME \| WORK \| OTHER |
| flat_building | VARCHAR(200) | NOT NULL | Flat/house number and building name |
| area_locality | VARCHAR(200) | NOT NULL | Area, locality, or street |
| city | VARCHAR(100) | NOT NULL | City name |
| state | VARCHAR(100) | NOT NULL | State name |
| pincode | CHAR(6) | NOT NULL | 6-digit Indian postal code |
| latitude | NUMERIC(10,7) | NOT NULL | Decimal degrees, WGS-84 |
| longitude | NUMERIC(10,7) | NOT NULL | Decimal degrees, WGS-84 |
| is_default | BOOLEAN | NOT NULL, default false | Whether this is the customer's default address |
| created_at | TIMESTAMPTZ | NOT NULL, default NOW() | Creation timestamp |
| updated_at | TIMESTAMPTZ | NOT NULL | Last update timestamp |
| deleted_at | TIMESTAMPTZ | nullable | Soft delete |

## Acceptance Criteria

- [ ] Given a customer with no saved addresses, when `POST /customers/me/addresses` is called with valid data, then the address is created and `is_default` is automatically set to `true` since it is the first address.
- [ ] Given a customer with 10 saved addresses, when `POST /customers/me/addresses` is called, then `422 ADDRESS_LIMIT_REACHED` is returned and no address is created.
- [ ] Given two addresses A (default) and B (not default), when `PATCH /addresses/B/set-default` is called, then address B has `is_default: true`, address A has `is_default: false`, and both changes occur in a single atomic transaction.
- [ ] Given an address that is the delivery address of an active order (`status: CONFIRMED`), when `DELETE /customers/me/addresses/:id` is called, then `409 ADDRESS_IN_ACTIVE_ORDER` is returned.
- [ ] Given valid GPS coordinates (12.9716, 77.5946), when `POST /customers/me/addresses/geocode` is called, then a `suggested_address` is returned with `city: "Bengaluru"` and a non-null `pincode`.
- [ ] Given a `PUT /customers/me/addresses/:id` request with a pincode of `56006` (5 digits), when the endpoint is called, then `400 VALIDATION_ERROR` is returned specifying that pincode must be exactly 6 digits.
- [ ] Given an address belonging to customer A, when customer B calls `DELETE /customers/me/addresses/:id` with that address ID, then `404 ADDRESS_NOT_FOUND` is returned (no cross-customer leakage).

## Dependencies

- EPIC-001 / STORY-001 - Customer must be authenticated
- EPIC-003 / STORY-001 - Order placement selects a saved address at checkout
- EPIC-000 / Infrastructure - Google Maps Geocoding API key and rate limit quota

## Notes

- Soft delete is used for addresses (`deleted_at`) so that order history retains a snapshot of the delivery address even after the customer deletes it. Order records should capture a denormalised snapshot of the address at order creation time.
- For the geocode endpoint, cache results keyed on `lat,lng` rounded to 4 decimal places with a 1-hour Redis TTL to reduce Google Maps API costs.
- Consider adding a `place_id` (Google Maps Place ID) field to addresses in a future iteration for more precise mapping and address auto-complete.
