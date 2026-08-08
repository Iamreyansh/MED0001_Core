package com.nammamedmate.rider.adapter.out.persistence;

import com.nammamedmate.rider.application.port.out.RiderShiftStore;
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
public class JdbcRiderShiftStore implements RiderShiftStore {

  private final JdbcTemplate jdbc;

  public JdbcRiderShiftStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(ShiftRecord shift) {
    jdbc.update(
        """
        INSERT INTO rider_shifts (
          id, rider_id, zone_id, shift_start, shift_end, duration_minutes,
          trips_in_shift, earnings_in_shift_paise, force_closed_by, created_at
        ) VALUES (?,?,?,?,?,?,?,?,?,?)
        """,
        shift.id(),
        shift.riderId(),
        shift.zoneId(),
        Timestamp.from(shift.shiftStart()),
        shift.shiftEnd() == null ? null : Timestamp.from(shift.shiftEnd()),
        shift.durationMinutes(),
        shift.tripsInShift(),
        shift.earningsInShiftPaise(),
        shift.forceClosedBy(),
        Timestamp.from(shift.createdAt()));
  }

  @Override
  public void close(UUID shiftId, Instant shiftEnd, int durationMinutes, UUID forceClosedBy) {
    jdbc.update(
        """
        UPDATE rider_shifts
        SET shift_end = ?, duration_minutes = ?, force_closed_by = ?
        WHERE id = ? AND shift_end IS NULL
        """,
        Timestamp.from(shiftEnd),
        durationMinutes,
        forceClosedBy,
        shiftId);
  }

  @Override
  public Optional<ShiftRecord> findOpenByRider(UUID riderId) {
    List<ShiftRecord> rows =
        jdbc.query(
            """
            SELECT * FROM rider_shifts
            WHERE rider_id = ? AND shift_end IS NULL
            ORDER BY shift_start DESC
            LIMIT 1
            """,
            this::map,
            riderId);
    return rows.stream().findFirst();
  }

  @Override
  public int sumDurationMinutesForRiderBetween(
      UUID riderId, Instant fromInclusive, Instant toExclusive) {
    Integer sum =
        jdbc.queryForObject(
            """
            SELECT COALESCE(SUM(
              CASE
                WHEN shift_end IS NOT NULL THEN duration_minutes
                ELSE GREATEST(
                  0,
                  CAST(EXTRACT(EPOCH FROM (LEAST(NOW(), ?) - shift_start)) / 60 AS INTEGER)
                )
              END
            ), 0)
            FROM rider_shifts
            WHERE rider_id = ?
              AND shift_start >= ?
              AND shift_start < ?
            """,
            Integer.class,
            Timestamp.from(toExclusive),
            riderId,
            Timestamp.from(fromInclusive),
            Timestamp.from(toExclusive));
    return java.util.Objects.requireNonNullElse(sum, 0);
  }

  @Override
  public Optional<ShiftRecord> findLatestClosedByRider(UUID riderId) {
    List<ShiftRecord> rows =
        jdbc.query(
            """
            SELECT * FROM rider_shifts
            WHERE rider_id = ? AND shift_end IS NOT NULL
            ORDER BY shift_end DESC
            LIMIT 1
            """,
            this::map,
            riderId);
    return rows.stream().findFirst();
  }

  private ShiftRecord map(ResultSet rs, int rowNum) throws SQLException {
    Timestamp end = rs.getTimestamp("shift_end");
    Integer duration = (Integer) rs.getObject("duration_minutes");
    return new ShiftRecord(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("rider_id"),
        (UUID) rs.getObject("zone_id"),
        rs.getTimestamp("shift_start").toInstant(),
        end == null ? null : end.toInstant(),
        duration,
        rs.getInt("trips_in_shift"),
        rs.getLong("earnings_in_shift_paise"),
        (UUID) rs.getObject("force_closed_by"),
        rs.getTimestamp("created_at").toInstant());
  }
}
