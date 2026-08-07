package com.nammamedmate.pharmacy.adapter.out.persistence;

import com.nammamedmate.pharmacy.application.port.out.PerformanceAlertStore;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcPerformanceAlertStore implements PerformanceAlertStore {

  private final JdbcTemplate jdbc;

  public JdbcPerformanceAlertStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(AlertRow row) {
    jdbc.update(
        """
        INSERT INTO performance_alert (
            id, pharmacy_id, alert_type, triggered_by, threshold_value, message, channels, sent_at
        ) VALUES (?, ?, ?::performance_alert_type, ?, ?, ?, ?, ?)
        """,
        row.id(),
        row.pharmacyId(),
        row.alertType(),
        row.triggeredBy(),
        row.thresholdValue(),
        row.message(),
        row.channels().toArray(String[]::new),
        Timestamp.from(row.sentAt()));
  }

  @Override
  public Optional<Instant> lastSentAt(UUID pharmacyId, String alertType, Instant since) {
    List<Instant> rows =
        jdbc.query(
            """
            SELECT sent_at FROM performance_alert
            WHERE pharmacy_id = ? AND alert_type = ?::performance_alert_type AND sent_at >= ?
            ORDER BY sent_at DESC LIMIT 1
            """,
            (rs, rowNum) -> ts(rs, "sent_at"),
            pharmacyId,
            alertType,
            Timestamp.from(since));
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  private static Instant ts(ResultSet rs, String col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }

  public static List<String> readChannels(ResultSet rs) throws SQLException {
    Array array = rs.getArray("channels");
    if (array == null) {
      return List.of();
    }
    String[] values = (String[]) array.getArray();
    return values == null ? List.of() : Arrays.asList(values);
  }

  public static AlertRow mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new AlertRow(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("pharmacy_id"),
        rs.getString("alert_type"),
        (UUID) rs.getObject("triggered_by"),
        rs.getBigDecimal("threshold_value"),
        rs.getString("message"),
        readChannels(rs),
        ts(rs, "sent_at"));
  }
}
