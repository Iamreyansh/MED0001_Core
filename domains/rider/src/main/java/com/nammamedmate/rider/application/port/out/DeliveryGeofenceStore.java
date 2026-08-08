package com.nammamedmate.rider.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeliveryGeofenceStore {

  record GeofenceRecord(
      UUID id,
      UUID zoneId,
      String zoneName,
      List<List<Double>> polygonCoordinates,
      BigDecimal areaSqKm,
      UUID createdBy,
      Instant createdAt,
      Instant updatedAt) {
    public GeofenceRecord {
      if (polygonCoordinates == null) {
        polygonCoordinates = List.of();
      } else {
        polygonCoordinates =
            List.copyOf(
                polygonCoordinates.stream()
                    .map(c -> c == null ? List.<Double>of() : List.copyOf(c))
                    .toList());
      }
    }
  }

  void insert(
      UUID id,
      UUID zoneId,
      String wkt,
      String coordinatesJson,
      BigDecimal areaSqKm,
      UUID createdBy,
      Instant now);

  boolean existsForZone(UUID zoneId);

  Optional<GeofenceRecord> findByZoneId(UUID zoneId);

  /** PostGIS ST_Within / ST_Covers — true when point is inside zone geofence. */
  boolean containsPoint(UUID zoneId, double lat, double lng);
}
