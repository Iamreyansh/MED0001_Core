package com.nammamedmate.rider.adapter.out.persistence;

import com.nammamedmate.rider.application.port.out.RiderLocationStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRiderLocationStore implements RiderLocationStore {

  private final JdbcTemplate jdbc;

  public JdbcRiderLocationStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insertBatch(List<LocationPoint> points) {
    if (points == null || points.isEmpty()) {
      return;
    }
    // ponytail: ≤60 pts/batch — simple loop beats JDBC batchUpdate ceremony
    for (LocationPoint p : points) {
      jdbc.update(
          """
          INSERT INTO rider_locations (
            id, rider_id, order_id, lat, lng, accuracy_m, speed_kmh, heading,
            low_accuracy, recorded_at, created_at
          ) VALUES (?,?,?,?,?,?,?,?,?,?,?)
          """,
          p.id(),
          p.riderId(),
          p.orderId(),
          p.lat(),
          p.lng(),
          p.accuracyM(),
          p.speedKmh(),
          p.heading(),
          p.lowAccuracy(),
          Timestamp.from(p.recordedAt()),
          Timestamp.from(p.createdAt()));
    }
  }

  @Override
  public List<LocationPoint> findByRiderAndOrder(UUID riderId, UUID orderId) {
    return jdbc.query(
        """
        SELECT * FROM rider_locations
        WHERE rider_id = ? AND order_id = ?
        ORDER BY recorded_at ASC
        """,
        this::map,
        riderId,
        orderId);
  }

  @Override
  public Optional<Instant> findOldestRecordedAt(UUID riderId, UUID orderId) {
    List<Instant> rows =
        jdbc.query(
            """
            SELECT MIN(recorded_at) AS oldest
            FROM rider_locations
            WHERE rider_id = ? AND order_id = ?
            """,
            (rs, i) -> {
              Timestamp ts = rs.getTimestamp("oldest");
              return ts == null ? null : ts.toInstant();
            },
            riderId,
            orderId);
    return rows.stream().filter(t -> t != null).findFirst();
  }

  @Override
  public int purgeOlderThan(Instant cutoff) {
    Integer n =
        jdbc.update("DELETE FROM rider_locations WHERE created_at < ?", Timestamp.from(cutoff));
    return n;
  }

  @Override
  public Optional<LocationPoint> findLatestByRider(UUID riderId) {
    List<LocationPoint> rows =
        jdbc.query(
            """
            SELECT * FROM rider_locations
            WHERE rider_id = ?
            ORDER BY recorded_at DESC
            LIMIT 1
            """,
            this::map,
            riderId);
    return rows.stream().findFirst();
  }

  private LocationPoint map(ResultSet rs, int rowNum) throws SQLException {
    Timestamp recorded = rs.getTimestamp("recorded_at");
    Timestamp created = rs.getTimestamp("created_at");
    return new LocationPoint(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("rider_id"),
        (UUID) rs.getObject("order_id"),
        rs.getBigDecimal("lat"),
        rs.getBigDecimal("lng"),
        rs.getBigDecimal("accuracy_m"),
        rs.getBigDecimal("speed_kmh"),
        rs.getBigDecimal("heading"),
        rs.getBoolean("low_accuracy"),
        recorded == null ? null : recorded.toInstant(),
        created == null ? null : created.toInstant());
  }
}
