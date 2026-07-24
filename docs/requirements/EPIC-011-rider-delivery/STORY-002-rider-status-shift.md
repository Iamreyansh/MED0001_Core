# STORY-002: Rider Availability & Shift Management

| Field | Value |
|---|---|
| Story ID | EPIC-011/STORY-002 |
| Epic | EPIC-011 - Rider Management and Delivery |
| Title | Rider Availability & Shift Management |
| Status | Draft |
| Priority | P0 |
| Estimated Effort | 1 Sprint |
| Last Updated | 2026-07-24 |

---

## Overview

This story manages the real-time availability of delivery riders on the platform. Riders toggle between ONLINE (available for assignments) and OFFLINE states via the Rider App; going ONLINE starts a shift session and begins broadcasting GPS location every 30 seconds. The admin fleet dashboard provides a live overview of every rider's status, zone, active order, earnings, and rating. Admins can force-change a rider's status and reassign them across zones. Zone coverage is computed in real time and categorised as COVERED, STRETCHED, or NO_RIDERS to inform dispatch decisions.

---

## User Roles

| Role | Capability |
|---|---|
| `rider` | Toggle own ONLINE/OFFLINE status, view current status and shift summary |
| `admin_operations` | View fleet overview, view zone rider breakdown, force-change rider status, reassign rider zone |
| `admin_super` | All admin_operations capabilities |

---

## Business Rules

| # | Rule |
|---|---|
| BR-001 | Only riders with `status = ACTIVE` (KYC approved, not blocked) can go ONLINE; any other status returns `RIDER_NOT_ELIGIBLE` error. |
| BR-002 | When a rider sets status to ONLINE, a new `RiderShift` record is created capturing `shift_start`, `zone_id`; when the rider goes OFFLINE the record is closed with `shift_end` and duration computed. |
| BR-003 | While ONLINE, the Rider App must POST a GPS location update every 30 seconds to `/api/v1/rider/location`; failure to update for > 2 minutes marks the rider as `STALE_GPS` in the fleet dashboard (no automatic status change). |
| BR-004 | If a rider goes OFFLINE while they have an `ON_TRIP` status (active delivery), the system raises an `OFFLINE_DURING_DELIVERY` alert to admin_operations; the rider's status is set to OFFLINE but the order is flagged for monitoring. |
| BR-005 | Zone coverage is computed as: `ratio = live_orders_in_zone / max(online_riders_in_zone, 1)`. `NO_RIDERS` = 0 online riders. `STRETCHED` = ratio > 0.7. `COVERED` = ratio ? 0.7 and at least 1 online rider. |
| BR-006 | A rider can optionally specify a `zone_id` when going ONLINE; if omitted the system uses their `primary_zone_id` from RiderProfile. |
| BR-007 | Admin force-change of status requires a `reason` field and creates an audit log entry; the reason is visible to the rider. |
| BR-008 | Shift duration accumulates across sessions within the same calendar day for incentive streak calculation; a 30-minute gap resets the continuous active session but not the daily streak count. |

---

## API Endpoints

### POST /api/v1/rider/status

**Auth:** `Bearer JWT` (rider)  
**Description:** Set rider availability status.

**Request Body:**
```json
{
  "status": "ONLINE",
  "zone_id": "zone_uuid_optional"
}
```

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "rider_id": "rider_uuid",
    "status": "ONLINE",
    "zone_id": "zone_uuid",
    "shift_id": "shift_uuid",
    "shift_started_at": "2026-07-24T08:00:00Z",
    "message": "You are now online and ready to receive orders."
  },
  "meta": {}
}
```

**Errors:**
| Code | HTTP | Meaning |
|---|---|---|
| `RIDER_NOT_ELIGIBLE` | 403 | Rider status is not ACTIVE (KYC not approved or blocked) |
| `INVALID_STATUS` | 422 | status not ONLINE or OFFLINE |
| `INVALID_ZONE` | 422 | zone_id does not exist |
| `OFFLINE_DURING_DELIVERY` | 409 | Cannot go OFFLINE while an order is ON_TRIP (warning returned; system sets OFFLINE but flags order) |

---

### GET /api/v1/rider/status

**Auth:** `Bearer JWT` (rider)  
**Description:** Get rider's current status, active order summary, shift stats, and today's earnings.

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "rider_id": "rider_uuid",
    "status": "ONLINE",
    "zone_id": "zone_uuid",
    "zone_name": "Koramangala",
    "shift_started_at": "2026-07-24T08:00:00Z",
    "shift_duration_minutes": 147,
    "active_order": {
      "order_id": "order_uuid",
      "order_status": "ON_TRIP",
      "customer_address_short": "HSR Layout, 2nd Sector",
      "eta_minutes": 8
    },
    "earnings_today": {
      "base": 375.00,
      "incentives": 50.00,
      "tips": 20.00,
      "total": 445.00
    },
    "daily_streak_days": 5
  },
  "meta": {}
}
```

---

### GET /api/v1/admin/riders/fleet

**Auth:** `Bearer JWT` (admin_operations, admin_super)  
**Description:** Live fleet overview with per-rider status, zone, and performance.

**Query Params:** `?zone_id=<uuid>&status=ONLINE|OFFLINE|ON_TRIP&page=1&limit=50`

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "summary": {
      "total_riders": 120,
      "online": 45,
      "on_trip": 28,
      "offline": 47,
      "stale_gps_count": 3
    },
    "riders": [
      {
        "rider_id": "rider_uuid",
        "name": "Ravi Kumar",
        "phone": "9876543210",
        "zone_id": "zone_uuid",
        "zone_name": "Koramangala",
        "vehicle_type": "BIKE",
        "status": "ON_TRIP",
        "active_order_id": "order_uuid",
        "avg_rating": 4.7,
        "on_time_pct": 91.2,
        "trips_today": 12,
        "earnings_today": 445.00,
        "last_location_at": "2026-07-24T09:27:00Z",
        "is_stale_gps": false
      }
    ]
  },
  "meta": {
    "page": 1,
    "limit": 50,
    "total": 120
  }
}
```

---

### GET /api/v1/admin/zones/:zone_id/riders

**Auth:** `Bearer JWT` (admin_operations, admin_super)  
**Description:** Riders in a specific zone with coverage status.

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "zone_id": "zone_uuid",
    "zone_name": "Koramangala",
    "coverage_status": "STRETCHED",
    "online_count": 5,
    "on_trip_count": 4,
    "offline_count": 8,
    "live_orders": 9,
    "coverage_ratio": 1.8,
    "avg_rating": 4.5,
    "riders": [
      {
        "rider_id": "rider_uuid",
        "name": "Ravi Kumar",
        "status": "ON_TRIP",
        "active_order_id": "order_uuid",
        "avg_rating": 4.7,
        "on_time_pct": 91.2
      }
    ]
  },
  "meta": {}
}
```

---

### PATCH /api/v1/admin/riders/:id/status

**Auth:** `Bearer JWT` (admin_operations, admin_super)  
**Description:** Admin force-changes a rider's status.

**Request Body:**
```json
{
  "status": "OFFLINE",
  "reason": "Rider unresponsive on phone; removing from dispatch pool."
}
```

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "rider_id": "rider_uuid",
    "status": "OFFLINE",
    "force_changed_by": "admin_uuid",
    "force_changed_at": "2026-07-24T10:30:00Z",
    "reason": "Rider unresponsive on phone; removing from dispatch pool."
  },
  "meta": {}
}
```

**Errors:**
| Code | HTTP | Meaning |
|---|---|---|
| `RIDER_NOT_FOUND` | 404 | rider_id does not exist |
| `REASON_REQUIRED` | 422 | reason is missing for admin action |
| `INVALID_STATUS` | 422 | status value not in ONLINE, OFFLINE |

---

### PATCH /api/v1/admin/riders/:id/zone

**Auth:** `Bearer JWT` (admin_operations, admin_super)  
**Description:** Reassign a rider to a different delivery zone.

**Request Body:**
```json
{
  "zone_id": "new_zone_uuid",
  "notify_rider": true
}
```

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "rider_id": "rider_uuid",
    "new_zone_id": "new_zone_uuid",
    "new_zone_name": "Indiranagar",
    "reassigned_by": "admin_uuid",
    "reassigned_at": "2026-07-24T10:35:00Z",
    "rider_notified": true
  },
  "meta": {}
}
```

**Errors:**
| Code | HTTP | Meaning |
|---|---|---|
| `RIDER_NOT_FOUND` | 404 | rider_id does not exist |
| `INVALID_ZONE` | 422 | zone_id does not exist |

---

## Data Models

### RiderShift

| Field | Type | Nullable | Description |
|---|---|---|---|
| `id` | UUID v4 | No | Primary key |
| `rider_id` | UUID | No | FK ? RiderProfile |
| `zone_id` | UUID | No | Zone active at shift start |
| `shift_start` | TIMESTAMPTZ | No | When rider went ONLINE |
| `shift_end` | TIMESTAMPTZ | Yes | When rider went OFFLINE (null = active) |
| `duration_minutes` | INTEGER | Yes | Computed on shift close |
| `trips_in_shift` | INTEGER | No | Orders completed during shift |
| `earnings_in_shift` | DECIMAL(12,2) | No | Earnings in this session |
| `force_closed_by` | UUID | Yes | Admin who force-closed the shift |
| `created_at` | TIMESTAMPTZ | No | Record creation time |

### RiderStatusAuditLog

| Field | Type | Nullable | Description |
|---|---|---|---|
| `id` | UUID v4 | No | Primary key |
| `rider_id` | UUID | No | FK ? RiderProfile |
| `changed_by` | UUID | No | rider_id (self) or admin_uuid |
| `changed_by_role` | ENUM(`rider`,`admin_operations`,`admin_super`) | No | Who made the change |
| `from_status` | VARCHAR(20) | No | Previous status |
| `to_status` | VARCHAR(20) | No | New status |
| `reason` | TEXT | Yes | Admin reason if force-change |
| `created_at` | TIMESTAMPTZ | No | Audit entry timestamp |

---

## Acceptance Criteria

| # | Criterion |
|---|---|
| AC-001 | A rider with `status = ACTIVE` can go ONLINE; a `RiderShift` record is created and GPS broadcasting begins. |
| AC-002 | A rider with `kyc_status != APPROVED` or `status = BLOCKED` receives HTTP 403 `RIDER_NOT_ELIGIBLE` when attempting to go ONLINE. |
| AC-003 | When a rider goes OFFLINE, the open `RiderShift` record is closed with `shift_end` timestamp and `duration_minutes` computed. |
| AC-004 | Going OFFLINE while an order is ON_TRIP returns the `OFFLINE_DURING_DELIVERY` response (status is set but order is flagged; admin receives an alert). |
| AC-005 | Fleet dashboard accurately reflects `online`, `on_trip`, and `offline` counts derived from live RiderProfile.status data. |
| AC-006 | Zone coverage_status is correctly computed: a zone with 5 live orders and 2 online riders returns `STRETCHED` (ratio = 2.5 > 0.7). |
| AC-007 | Admin force-changing a rider status creates an entry in `RiderStatusAuditLog` with the reason and admin ID. |
| AC-008 | A rider offline for > 2 minutes without a GPS update is marked `is_stale_gps = true` in the fleet list without changing their status. |

---

## Dependencies

| Dependency | Type | Notes |
|---|---|---|
| Auth Service (EPIC-001) | Internal | JWT decode for rider authentication |
| GPS Location Service (EPIC-011/STORY-004) | Internal | Location posting begins on ONLINE status |
| Redis | External | Live rider status cache for fleet dashboard performance |
| Notification Service (EPIC-013) | Internal | Push to rider on force-status change or zone reassignment |
| Order Management (EPIC-010) | Internal | `active_order` lookup; ON_TRIP detection |

---

## Notes

- The `is_stale_gps` flag is a computed property derived from the difference between `now()` and the rider's last `RiderLocation.created_at`; it is not stored but computed on fleet list queries.
- Zone coverage ratio is recalculated on every fleet dashboard request; no persistent cache needed at v1 scale.
- OFFLINE_DURING_DELIVERY is a **non-blocking** condition in v1 - the API returns success (status set to OFFLINE) but emits an internal alert; the operations team handles follow-up manually.
