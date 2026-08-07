package com.nammamedmate.pharmacy.adapter.out.persistence;

import com.nammamedmate.pharmacy.application.port.out.PharmacyCallLogStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcPharmacyCallLogStore implements PharmacyCallLogStore {

  private final JdbcTemplate jdbc;

  public JdbcPharmacyCallLogStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(CallLogRow row) {
    jdbc.update(
        """
        INSERT INTO pharmacy_call_log (
            id, pharmacy_id, duration_seconds, call_outcome, notes, logged_by, logged_at
        ) VALUES (?, ?, ?, ?::call_outcome, ?, ?, ?)
        """,
        row.id(),
        row.pharmacyId(),
        row.durationSeconds(),
        row.callOutcome(),
        row.notes(),
        row.loggedBy(),
        Timestamp.from(row.loggedAt()));
  }

  public static CallLogRow mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new CallLogRow(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("pharmacy_id"),
        rs.getInt("duration_seconds"),
        rs.getString("call_outcome"),
        rs.getString("notes"),
        (UUID) rs.getObject("logged_by"),
        ts(rs, "logged_at"));
  }

  private static Instant ts(ResultSet rs, String col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }
}
