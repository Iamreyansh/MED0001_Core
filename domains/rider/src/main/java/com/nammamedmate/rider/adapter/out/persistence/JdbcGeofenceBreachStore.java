package com.nammamedmate.rider.adapter.out.persistence;

import com.nammamedmate.rider.application.port.out.GeofenceBreachStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcGeofenceBreachStore implements GeofenceBreachStore {

  private final JdbcTemplate jdbc;

  public JdbcGeofenceBreachStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(BreachRecord row) {
    jdbc.update(
        """
        INSERT INTO geofence_breach_events (
          id, rider_id, zone_id, order_id, breach_lat, breach_lng, alert_sent, detected_at
        ) VALUES (?,?,?,?,?,?,?,?)
        """,
        row.id(),
        row.riderId(),
        row.zoneId(),
        row.orderId(),
        row.breachLat(),
        row.breachLng(),
        row.alertSent(),
        Timestamp.from(row.detectedAt()));
  }

  @Override
  public boolean existsSince(UUID riderId, UUID zoneId, Instant since) {
    Integer n =
        jdbc.queryForObject(
            """
            SELECT COUNT(1) FROM geofence_breach_events
            WHERE rider_id = ? AND zone_id = ? AND detected_at >= ?
            """,
            Integer.class,
            riderId,
            zoneId,
            Timestamp.from(since));
    if (n == null) {
      return false;
    }
    return n > 0;
  }
}
