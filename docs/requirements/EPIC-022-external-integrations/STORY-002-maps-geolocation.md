# STORY-002: Maps & Geolocation

| Field        | Value                          |
| ------------ | ------------------------------ |
| Story ID     | EPIC-022-STORY-002             |
| Epic         | EPIC-022 External Integrations |
| Title        | Maps & Geolocation             |
| Priority     | P1                             |
| Status       | In Development                 |
| Role         | Internal service               |
| Last Updated | 2026-07-24                     |

## Overview

The Maps & Geolocation story integrates Google Maps Platform APIs to power four core platform capabilities: address geocoding for customer delivery addresses, reverse geocoding for address display from coordinates, distance matrix calculation for pharmacy selection scoring and ETA estimation, directions API for rider navigation routes, and server-side zone polygon checking (no Google API call needed). All maps API calls are logged for cost tracking, and geocoding results are cached for 24 hours to minimize API spend.

## User Roles

| Role              | Access                                       |
| ----------------- | -------------------------------------------- |
| Internal services | Call all maps endpoints (service-to-service) |
| admin_operations  | View maps API cost and usage stats           |

## Business Rules

1. **API Key Segregation**: Separate Google Maps API keys are used per use case (Geocoding API key, Distance Matrix API key, Directions API key, Places API key). This enables granular cost tracking and quota management per capability.
2. **Geocode Cache**: Geocoding results (address ? lat/lng) are cached in Redis for 24 hours using the address string as the cache key (normalized: lowercase, trimmed). Cache hit avoids an API call entirely.
3. **Distance Matrix Usage**: Used for smart pharmacy selection (rank pharmacies by ETA to customer) and delivery ETA estimation. Called with up to 10 origins - 10 destinations per request (Google limit: 25 origins - 25 destinations per request).
4. **Zone Check (Server-Side)**: The zone_check endpoint determines if a point is inside a zone's GeoJSON polygon using server-side ray-casting algorithm. No Google API call is made for zone checks.
5. **Directions API for Rider Navigation**: The Directions API returns a polyline and step-by-step navigation for the rider app. Called only when the rider starts a delivery trip. Results are not cached (dynamic traffic conditions).
6. **Cost Monitoring**: Daily Google Maps API spend is aggregated per API type and compared against budget. If daily spend exceeds Rs 500, an alert fires (EPIC-020 monitoring alert type: MAPS_BUDGET_EXCEEDED).
7. **Reverse Geocode for Display**: When a customer drops a pin on the map (lat/lng), reverse geocoding returns a formatted address for confirmation. Results are cached for 1 hour by lat/lng coordinate rounded to 4 decimal places.
8. **All Calls Logged**: Every Google Maps API call is logged with: timestamp, api_type, request_params (without full address for privacy), response_code, latency_ms, was_cache_hit, estimated_cost_rs.
9. **Travel Mode**: All delivery distance/time calculations use `DRIVING` mode. `BICYCLING` mode is optionally supported for electric two-wheeler riders (configurable per rider profile).
10. **Error Handling**: If Google Maps API returns a non-OK status (e.g., `ZERO_RESULTS`, `OVER_DAILY_LIMIT`), the endpoint returns a 422 with the Google status code and a fallback suggestion (e.g., use last known address).

## API Endpoints

### POST /api/v1/integrations/maps/geocode

Convert a text address to latitude/longitude coordinates.

**Auth**: Service-to-service JWT (internal only)

**Request Body**

```json
{
	"address": "12, 5th Cross, Indiranagar 1st Stage",
	"city": "Bangalore",
	"pincode": "560038"
}
```

**Response 200**

```json
{
	"success": true,
	"data": {
		"lat": 12.9716,
		"lng": 77.6412,
		"formatted_address": "12, 5th Cross Rd, Indiranagar Stage 1, Indiranagar, Bengaluru, Karnataka 560038, India",
		"place_id": "ChIJr4vVJXoUrjsRn-JBWLF2j3U",
		"accuracy": "ROOFTOP",
		"cache_hit": false,
		"cached_at": null
	},
	"meta": {}
}
```

**Error Table**

| HTTP Code | Error Code           | Condition                            |
| --------- | -------------------- | ------------------------------------ |
| 422       | GEOCODE_NO_RESULTS   | Google returned ZERO_RESULTS         |
| 422       | GEOCODE_AMBIGUOUS    | Multiple results with low confidence |
| 503       | MAPS_API_UNAVAILABLE | Google Maps API unreachable          |

---

### POST /api/v1/integrations/maps/reverse-geocode

Convert lat/lng to a human-readable address.

**Auth**: Service-to-service JWT (internal only)

**Request Body**

```json
{
	"lat": 12.9716,
	"lng": 77.6412
}
```

**Response 200**

```json
{
	"success": true,
	"data": {
		"formatted_address": "12, 5th Cross Rd, Indiranagar, Bengaluru, Karnataka 560038, India",
		"area_locality": "Indiranagar",
		"city": "Bengaluru",
		"state": "Karnataka",
		"pincode": "560038",
		"place_id": "ChIJr4vVJXoUrjsRn-JBWLF2j3U",
		"cache_hit": true
	},
	"meta": {}
}
```

---

### POST /api/v1/integrations/maps/distance-matrix

Calculate distances and travel durations between multiple origins and destinations.

**Auth**: Service-to-service JWT (internal only)

**Request Body**

```json
{
	"origins": [{ "lat": 12.9716, "lng": 77.6412 }],
	"destinations": [
		{ "lat": 12.9784, "lng": 77.6408 },
		{ "lat": 12.9652, "lng": 77.648 }
	],
	"mode": "DRIVING"
}
```

**Response 200**

```json
{
	"success": true,
	"data": {
		"matrix": [
			{
				"origin_index": 0,
				"destination_index": 0,
				"distance_meters": 1240,
				"duration_seconds": 312,
				"status": "OK"
			},
			{
				"origin_index": 0,
				"destination_index": 1,
				"distance_meters": 2180,
				"duration_seconds": 540,
				"status": "OK"
			}
		]
	},
	"meta": {}
}
```

---

### POST /api/v1/integrations/maps/directions

Get a navigation route for a rider.

**Auth**: Service-to-service JWT (internal only)

**Request Body**

```json
{
	"origin": { "lat": 12.9716, "lng": 77.6412 },
	"destination": { "lat": 12.9784, "lng": 77.6408 },
	"mode": "DRIVING"
}
```

**Response 200**

```json
{
	"success": true,
	"data": {
		"route_polyline": "a~l~Fjk~uOwHJy@P",
		"distance_meters": 1240,
		"duration_seconds": 312,
		"duration_in_traffic_seconds": 380,
		"steps": [
			{
				"instruction": "Head north on 5th Cross Rd",
				"distance_meters": 420,
				"duration_seconds": 84
			}
		]
	},
	"meta": {}
}
```

---

### POST /api/v1/integrations/maps/zone-check

Check if a lat/lng point is inside a zone polygon (server-side computation, no Google API).

**Auth**: Service-to-service JWT (internal only)

**Request Body**

```json
{
	"point": { "lat": 12.9716, "lng": 77.6412 },
	"polygon_coordinates": [
		[12.96, 77.62],
		[12.96, 77.66],
		[12.99, 77.66],
		[12.99, 77.62],
		[12.96, 77.62]
	],
	"zone_id": "uuid-zone-1"
}
```

**Response 200**

```json
{
	"success": true,
	"data": {
		"inside": true,
		"zone_id": "uuid-zone-1",
		"distance_to_boundary_meters": null
	},
	"meta": {}
}
```

---

## Data Models

### maps_api_call_log

| Column            | Type         | Notes                                                             |
| ----------------- | ------------ | ----------------------------------------------------------------- |
| id                | UUID         | PK                                                                |
| api_type          | VARCHAR(20)  | GEOCODE, REVERSE_GEOCODE, DISTANCE_MATRIX, DIRECTIONS, ZONE_CHECK |
| request_summary   | VARCHAR(200) | Sanitized summary (no full PII addresses)                         |
| response_status   | VARCHAR(20)  | OK, ZERO_RESULTS, OVER_LIMIT, ERROR                               |
| latency_ms        | INTEGER      |                                                                   |
| was_cache_hit     | BOOLEAN      |                                                                   |
| estimated_cost_rs | DECIMAL(6,4) | Based on Google Maps pricing tiers                                |
| called_at         | TIMESTAMPTZ  |                                                                   |
| calling_service   | VARCHAR(50)  | e.g., dispatch, order_management                                  |

### maps_geocode_cache

| Column            | Type          | Notes                          |
| ----------------- | ------------- | ------------------------------ |
| cache_key         | VARCHAR(500)  | PK - normalized address string |
| lat               | DECIMAL(10,7) |                                |
| lng               | DECIMAL(10,7) |                                |
| formatted_address | TEXT          |                                |
| place_id          | VARCHAR(100)  |                                |
| cached_at         | TIMESTAMPTZ   |                                |
| expires_at        | TIMESTAMPTZ   | cached_at + 24 hours           |

## Acceptance Criteria

1. **AC-001**: POST /geocode for a previously geocoded address returns `cache_hit: true` and does not call Google Maps API.
2. **AC-002**: POST /zone-check uses server-side ray-casting; no Google API call is made; response returns within 10ms.
3. **AC-003**: POST /distance-matrix with > 25 origins returns `422 TOO_MANY_ORIGINS` (Google API limit).
4. **AC-004**: Daily maps API cost alert fires when daily spend exceeds Rs 500 (based on aggregated `maps_api_call_log`).
5. **AC-005**: POST /geocode returns `422 GEOCODE_NO_RESULTS` when Google returns `ZERO_RESULTS` status.
6. **AC-006**: POST /directions returns `duration_in_traffic_seconds` (includes real-time traffic) in addition to `duration_seconds` (historical average).
7. **AC-007**: All maps API calls (including cache hits) are logged in `maps_api_call_log` with `was_cache_hit` field.
8. **AC-008**: Geocoding API key, Distance Matrix key, and Directions key are separate keys loaded from AWS Secrets Manager (not shared keys).

## Dependencies

| Dependency                | Type             | Notes                                  |
| ------------------------- | ---------------- | -------------------------------------- |
| Google Maps Platform      | External         | Geocoding, Distance Matrix, Directions |
| Redis                     | Infrastructure   | Geocode result caching                 |
| AWS Secrets Manager       | Credential store | API keys                               |
| EPIC-004 Dispatch         | Consumer         | Distance matrix for pharmacy scoring   |
| EPIC-007 Rider            | Consumer         | Directions for navigation              |
| EPIC-001 Order Management | Consumer         | Geocode for delivery address           |

## Notes

- The `route_polyline` is an encoded Google Polyline string. The rider app decodes it using the Google Maps SDK for rendering.
- Zone polygon storage is in the `zones.boundary_geojson` column (EPIC-016 STORY-005). The `zone-check` endpoint accepts inline polygon coordinates for flexibility, or the platform can look up the polygon from the zone_id directly.
- Google Maps pricing (2026): Geocoding - $5/1,000 requests (~Rs 420/1,000); Distance Matrix - $10/1,000 elements; Directions - $10/1,000 requests. Rs 500/day budget ? 1,000-1,200 API calls/day.
