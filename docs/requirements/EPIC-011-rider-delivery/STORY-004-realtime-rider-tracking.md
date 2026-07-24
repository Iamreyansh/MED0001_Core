# STORY-004: Real-Time Rider GPS Tracking

| Field | Value |
|---|---|
| Story ID | EPIC-011/STORY-004 |
| Epic | EPIC-011 - Rider Management and Delivery |
| Title | Real-Time Rider GPS Tracking |
| Status | Draft |
| Priority | P0 |
| Estimated Effort | 2 Sprints |
| Last Updated | 2026-07-24 |

---

## Overview

This story covers the ingestion, storage, and real-time delivery of GPS location data for delivery riders. When a rider is ONLINE, the Rider App batches and posts location updates every 30 seconds. Customers with an active `OUT_FOR_DELIVERY` order see the rider's live position with an ETA on the map in the Customer App, delivered via WebSocket or Server-Sent Events. Admins can view any rider's live location and their complete location trail for a given order. Zone geofences are maintained and breach events are generated when a rider travels outside their assigned delivery zone.

---

## User Roles

| Role | Capability |
|---|---|
| `rider` | Post GPS location, create geofence zones (admin-initiated push) |
| `customer` | View live rider location and ETA for own active order only |
| `admin_operations` | View any rider's live location, view location history, create geofences |
| `admin_super` | All admin_operations capabilities |

---

## Business Rules

| # | Rule |
|---|---|
| BR-001 | GPS location updates are only accepted from riders with `status = ONLINE` or `status = ON_TRIP`; updates from OFFLINE/BLOCKED riders are silently discarded. |
| BR-002 | Location updates with `accuracy > 50 metres` are stored but flagged as `low_accuracy = true`; they are excluded from ETA calculations. |
| BR-003 | Location points are sent by the Rider App as a **batch** every 30 seconds containing all GPS readings captured in that interval; the server ingests each point individually into the time-series store. |
| BR-004 | The **customer** only receives the rider's live location when the order status is `OUT_FOR_DELIVERY`; requests for orders in any other status return `LOCATION_NOT_AVAILABLE`. |
| BR-005 | ETA is recalculated on each location update using the Google Maps Distance Matrix API (`driving` mode); the latest ETA is pushed to the customer via WebSocket/SSE within 2 seconds of the location update being ingested. |
| BR-006 | Location history is retained for **30 days**; data older than 30 days is archived or purged by a scheduled job. |
| BR-007 | A **geofence breach** is detected when a rider's location falls outside the polygon of their assigned delivery zone; a breach event is logged and an alert is sent to admin_operations. A single breach within a 5-minute window raises only one alert (debounced). |
| BR-008 | Live location data is stored in **Redis** (current position per rider keyed by `rider_id`); historical trail is persisted to **PostgreSQL** `rider_locations` table. Redis entry TTL = 5 minutes; a stale entry means the rider has gone offline. |

---

## API Endpoints

### POST /api/v1/rider/location

**Auth:** `Bearer JWT` (rider)  
**Description:** Rider app posts a batch of GPS location readings.

**Request Body:**
```json
{
  "points": [
    {
      "lat": 12.9352,
      "lng": 77.6245,
      "accuracy": 10.5,
      "speed": 22.4,
      "heading": 135.0,
      "timestamp": "2026-07-24T09:20:00Z"
    },
    {
      "lat": 12.9347,
      "lng": 77.6251,
      "accuracy": 12.1,
      "speed": 21.0,
      "heading": 138.0,
      "timestamp": "2026-07-24T09:20:10Z"
    }
  ]
}
```

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "points_received": 2,
    "points_stored": 2,
    "points_flagged_low_accuracy": 0,
    "latest_position": {
      "lat": 12.9347,
      "lng": 77.6251,
      "stored_at": "2026-07-24T09:20:10Z"
    }
  },
  "meta": {}
}
```

**Errors:**
| Code | HTTP | Meaning |
|---|---|---|
| `RIDER_OFFLINE` | 422 | Rider status is not ONLINE or ON_TRIP; update discarded |
| `EMPTY_POINTS_ARRAY` | 422 | points array is empty |
| `POINTS_LIMIT_EXCEEDED` | 422 | Batch contains more than 60 points |

---

### GET /api/v1/orders/:order_id/rider-location

**Auth:** `Bearer JWT` (customer)  
**Description:** Customer fetches rider's current live position for their active order.

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "order_id": "order_uuid",
    "rider_id": "rider_uuid",
    "rider_name": "Ravi",
    "lat": 12.9347,
    "lng": 77.6251,
    "heading": 138.0,
    "speed_kmh": 21.0,
    "last_updated_at": "2026-07-24T09:20:10Z",
    "eta_minutes": 8,
    "distance_remaining_km": 1.9,
    "websocket_channel": "ws://api.nammamedmate.com/ws/order/order_uuid/rider-location"
  },
  "meta": {}
}
```

**Errors:**
| Code | HTTP | Meaning |
|---|---|---|
| `ORDER_NOT_FOUND` | 404 | order_id does not exist |
| `NOT_YOUR_ORDER` | 403 | Order belongs to a different customer |
| `LOCATION_NOT_AVAILABLE` | 422 | Order not in OUT_FOR_DELIVERY state |
| `RIDER_LOCATION_STALE` | 200 | Location returned but `last_updated_at` > 2 minutes ago (informational flag) |

---

### GET /api/v1/admin/riders/:id/location

**Auth:** `Bearer JWT` (admin_operations, admin_super)  
**Description:** Admin fetches current live location for a specific rider.

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "rider_id": "rider_uuid",
    "name": "Ravi Kumar",
    "status": "ON_TRIP",
    "lat": 12.9347,
    "lng": 77.6251,
    "heading": 138.0,
    "speed_kmh": 21.0,
    "accuracy_m": 12.1,
    "last_updated_at": "2026-07-24T09:20:10Z",
    "is_stale": false,
    "zone_id": "zone_uuid",
    "is_in_zone": true,
    "active_order_id": "order_uuid"
  },
  "meta": {}
}
```

---

### GET /api/v1/admin/riders/:id/location-history

**Auth:** `Bearer JWT` (admin_operations, admin_super)  
**Description:** Full GPS trail for a rider during a specific order.

**Query Params:** `?order_id=<uuid>` (required)

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "rider_id": "rider_uuid",
    "order_id": "order_uuid",
    "total_points": 48,
    "distance_km": 2.4,
    "points": [
      {
        "lat": 12.9352,
        "lng": 77.6245,
        "accuracy": 10.5,
        "speed_kmh": 22.4,
        "heading": 135.0,
        "low_accuracy": false,
        "timestamp": "2026-07-24T09:20:00Z"
      }
    ]
  },
  "meta": {}
}
```

**Errors:**
| Code | HTTP | Meaning |
|---|---|---|
| `RIDER_NOT_FOUND` | 404 | rider_id does not exist |
| `ORDER_ID_REQUIRED` | 422 | order_id query param missing |
| `HISTORY_EXPIRED` | 410 | Location history older than 30 days |

---

### POST /api/v1/admin/geofences

**Auth:** `Bearer JWT` (admin_operations, admin_super)  
**Description:** Create or update a delivery zone geofence polygon.

**Request Body:**
```json
{
  "zone_id": "zone_uuid",
  "polygon_coordinates": [
    [12.9200, 77.6100],
    [12.9450, 77.6100],
    [12.9450, 77.6400],
    [12.9200, 77.6400],
    [12.9200, 77.6100]
  ]
}
```
*(First and last coordinate must be identical to close the polygon.)*

**Response 201 Created:**
```json
{
  "success": true,
  "data": {
    "geofence_id": "geofence_uuid",
    "zone_id": "zone_uuid",
    "zone_name": "Koramangala",
    "polygon_coordinates": [[12.92, 77.61], "..."],
    "area_sq_km": 4.8,
    "created_at": "2026-07-24T10:00:00Z"
  },
  "meta": {}
}
```

**Errors:**
| Code | HTTP | Meaning |
|---|---|---|
| `ZONE_NOT_FOUND` | 404 | zone_id does not exist |
| `INVALID_POLYGON` | 422 | Polygon not closed or has < 3 points |
| `GEOFENCE_ALREADY_EXISTS` | 409 | Zone already has a geofence; use PATCH to update |

---

## Data Models

### RiderLocation (PostgreSQL - time-series)

| Field | Type | Nullable | Description |
|---|---|---|---|
| `id` | UUID v4 | No | Primary key |
| `rider_id` | UUID | No | FK ? RiderProfile; indexed |
| `order_id` | UUID | Yes | FK ? Order (if on active delivery) |
| `lat` | DECIMAL(10,7) | No | Latitude |
| `lng` | DECIMAL(10,7) | No | Longitude |
| `accuracy_m` | DECIMAL(7,2) | Yes | GPS accuracy in metres |
| `speed_kmh` | DECIMAL(6,2) | Yes | Speed in km/h |
| `heading` | DECIMAL(6,2) | Yes | Compass heading in degrees |
| `low_accuracy` | BOOLEAN | No | True if accuracy_m > 50 |
| `recorded_at` | TIMESTAMPTZ | No | Device-reported timestamp |
| `created_at` | TIMESTAMPTZ | No | Server ingestion timestamp |

*Partitioned by month on `created_at`. Partition retention = 30 days.*

---

### RiderLocationRedis (Redis hash - live position)

| Key | Format | Description |
|---|---|---|
| Key | `rider_location:{rider_id}` | Hash key |
| `lat` | String | Current latitude |
| `lng` | String | Current longitude |
| `heading` | String | Current heading |
| `speed_kmh` | String | Current speed |
| `order_id` | String | Current order_id (empty if none) |
| `updated_at` | ISO 8601 | Last update timestamp |
| TTL | 5 minutes | Auto-expire on inactivity |

---

### DeliveryGeofence

| Field | Type | Nullable | Description |
|---|---|---|---|
| `id` | UUID v4 | No | Primary key |
| `zone_id` | UUID | No | FK ? DeliveryZone; unique |
| `polygon` | GEOGRAPHY(POLYGON) | No | PostGIS polygon |
| `area_sq_km` | DECIMAL(8,3) | No | Computed polygon area |
| `created_by` | UUID | No | FK ? AdminUser |
| `created_at` | TIMESTAMPTZ | No | Creation timestamp |
| `updated_at` | TIMESTAMPTZ | No | Last update timestamp |

---

### GeofenceBreachEvent

| Field | Type | Nullable | Description |
|---|---|---|---|
| `id` | UUID v4 | No | Primary key |
| `rider_id` | UUID | No | FK ? RiderProfile |
| `zone_id` | UUID | No | Zone that was breached |
| `order_id` | UUID | Yes | Active order at time of breach |
| `breach_lat` | DECIMAL(10,7) | No | Location at breach |
| `breach_lng` | DECIMAL(10,7) | No | Location at breach |
| `alert_sent` | BOOLEAN | No | Whether admin alert was dispatched |
| `detected_at` | TIMESTAMPTZ | No | Timestamp of breach detection |

---

## Acceptance Criteria

| # | Criterion |
|---|---|
| AC-001 | A batch of GPS points from an ONLINE rider is ingested; the latest position is updated in Redis and all points are persisted to PostgreSQL. |
| AC-002 | A location update with `accuracy > 50 m` is stored with `low_accuracy = true` and is excluded from the ETA calculation. |
| AC-003 | `GET /orders/:id/rider-location` for an order in `OUT_FOR_DELIVERY` returns the rider's current lat/lng and ETA. |
| AC-004 | `GET /orders/:id/rider-location` for an order not in `OUT_FOR_DELIVERY` returns HTTP 422 `LOCATION_NOT_AVAILABLE`. |
| AC-005 | ETA is recalculated after each valid location ingestion and pushed to the WebSocket channel for the corresponding order. |
| AC-006 | A geofence breach triggers a `GeofenceBreachEvent` record and sends an alert to admin_operations; duplicate breach alerts within a 5-minute window are suppressed. |
| AC-007 | Location history older than 30 days is purged by the scheduled job; `GET /admin/riders/:id/location-history` for purged data returns HTTP 410 `HISTORY_EXPIRED`. |
| AC-008 | Admin `GET /admin/riders/:id/location` returns `is_stale = true` when last GPS update is > 2 minutes ago. |

---

## Dependencies

| Dependency | Type | Notes |
|---|---|---|
| Redis | External | Live position hash store; TTL-based staleness detection |
| PostgreSQL (PostGIS extension) | Internal | Polygon geofence storage; point-in-polygon queries |
| Google Maps Distance Matrix API | External | ETA calculation per location update |
| WebSocket / SSE Server | Internal | Real-time push of location + ETA to Customer App |
| Rider Status (EPIC-011/STORY-002) | Internal | Only ONLINE/ON_TRIP riders post location |
| Order Management (EPIC-010) | Internal | Order status gating for customer location access |
| Scheduled Job Runner | Internal | 30-day location history purge job |

---

## Notes

- The WebSocket channel URL returned in `GET /orders/:id/rider-location` is the connection point for the Customer App to subscribe to live updates; the server pushes new lat/lng + ETA to all subscribers of that channel on each location ingestion.
- For v1, Server-Sent Events (SSE) may be used as a simpler fallback if WebSocket infrastructure is not ready; the API contract is the same.
- The `recorded_at` (device timestamp) vs `created_at` (server timestamp) delta is logged for latency monitoring; if delta > 60 seconds the point is treated as stale and not used for ETA but still stored.
- PostGIS `ST_Within(rider_point, zone_polygon)` is used for geofence breach detection; this query runs on every batch ingestion for riders with an active order.
