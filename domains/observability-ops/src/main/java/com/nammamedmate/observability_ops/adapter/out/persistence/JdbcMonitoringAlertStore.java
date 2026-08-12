package com.nammamedmate.observability_ops.adapter.out.persistence;

import com.nammamedmate.observability_ops.application.port.out.MonitoringAlertStore;
import com.nammamedmate.observability_ops.domain.AlertListStatus;
import com.nammamedmate.observability_ops.domain.AlertSeverity;
import com.nammamedmate.observability_ops.domain.AlertType;
import com.nammamedmate.observability_ops.domain.MonitoringAlert;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

@Component
public class JdbcMonitoringAlertStore implements MonitoringAlertStore {

  private static final RowMapper<MonitoringAlert> ROW =
      (rs, i) ->
          new MonitoringAlert(
              (UUID) rs.getObject("id"),
              AlertSeverity.valueOf(rs.getString("severity")),
              AlertType.valueOf(rs.getString("type")),
              rs.getString("message"),
              rs.getString("triggering_metric"),
              rs.getBigDecimal("triggering_value"),
              rs.getBigDecimal("threshold_value"),
              (UUID) rs.getObject("zone_id"),
              rs.getTimestamp("triggered_at").toInstant(),
              rs.getBoolean("acknowledged"),
              (UUID) rs.getObject("acknowledged_by"),
              rs.getTimestamp("acknowledged_at") == null
                  ? null
                  : rs.getTimestamp("acknowledged_at").toInstant(),
              rs.getString("acknowledged_notes"),
              rs.getBoolean("auto_remediated"),
              rs.getTimestamp("resolved_at") == null
                  ? null
                  : rs.getTimestamp("resolved_at").toInstant(),
              rs.getString("resolution_reason"));

  private final JdbcTemplate jdbc;

  public JdbcMonitoringAlertStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<MonitoringAlert> findById(UUID id) {
    List<MonitoringAlert> rows =
        jdbc.query("SELECT * FROM monitoring_alerts WHERE id = ?", ROW, id);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<MonitoringAlert> findOpen(AlertType type, UUID zoneId) {
    List<MonitoringAlert> rows =
        zoneId == null
            ? jdbc.query(
                """
                SELECT * FROM monitoring_alerts
                WHERE type = ? AND zone_id IS NULL AND resolved_at IS NULL
                LIMIT 1
                """,
                ROW,
                type.name())
            : jdbc.query(
                """
                SELECT * FROM monitoring_alerts
                WHERE type = ? AND zone_id = ? AND resolved_at IS NULL
                LIMIT 1
                """,
                ROW,
                type.name(),
                zoneId);
    return rows.stream().findFirst();
  }

  @Override
  public List<MonitoringAlert> findOpen() {
    return jdbc.query("SELECT * FROM monitoring_alerts WHERE resolved_at IS NULL", ROW);
  }

  @Override
  public MonitoringAlert insert(MonitoringAlert alert) {
    jdbc.update(
        """
        INSERT INTO monitoring_alerts (
          id, severity, type, message, triggering_metric, triggering_value, threshold_value,
          zone_id, triggered_at, acknowledged, acknowledged_by, acknowledged_at, acknowledged_notes,
          auto_remediated, resolved_at, resolution_reason)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        alert.id(),
        alert.severity().name(),
        alert.type().name(),
        alert.message(),
        alert.triggeringMetric(),
        alert.triggeringValue(),
        alert.thresholdValue(),
        alert.zoneId(),
        Timestamp.from(alert.triggeredAt()),
        alert.acknowledged(),
        alert.acknowledgedBy(),
        alert.acknowledgedAt() == null ? null : Timestamp.from(alert.acknowledgedAt()),
        alert.acknowledgedNotes(),
        alert.autoRemediated(),
        alert.resolvedAt() == null ? null : Timestamp.from(alert.resolvedAt()),
        alert.resolutionReason());
    return alert;
  }

  @Override
  public void updateTriggeredAt(UUID id, Instant triggeredAt) {
    jdbc.update(
        "UPDATE monitoring_alerts SET triggered_at = ? WHERE id = ?",
        Timestamp.from(triggeredAt),
        id);
  }

  @Override
  public void acknowledge(UUID id, UUID by, Instant at, String notes) {
    jdbc.update(
        """
        UPDATE monitoring_alerts
        SET acknowledged = TRUE, acknowledged_by = ?, acknowledged_at = ?, acknowledged_notes = ?
        WHERE id = ?
        """,
        by,
        Timestamp.from(at),
        notes,
        id);
  }

  @Override
  public void resolve(UUID id, Instant resolvedAt, String reason) {
    jdbc.update(
        """
        UPDATE monitoring_alerts
        SET resolved_at = ?, resolution_reason = ?
        WHERE id = ?
        """,
        Timestamp.from(resolvedAt),
        reason,
        id);
  }

  @Override
  public void markAutoRemediated(UUID id, boolean value) {
    jdbc.update("UPDATE monitoring_alerts SET auto_remediated = ? WHERE id = ?", value, id);
  }

  @Override
  public Page list(AlertListStatus status, AlertSeverity severity, int page, int limit) {
    StringBuilder where = new StringBuilder(" WHERE 1=1 ");
    List<Object> args = new ArrayList<>();
    if (status == AlertListStatus.ACTIVE) {
      where.append(" AND resolved_at IS NULL AND acknowledged = FALSE ");
    } else if (status == AlertListStatus.ACKNOWLEDGED) {
      where.append(" AND resolved_at IS NULL AND acknowledged = TRUE ");
    } else {
      where.append(" AND resolved_at IS NOT NULL ");
    }
    if (severity != null) {
      where.append(" AND severity = ? ");
      args.add(severity.name());
    }
    Long total =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM monitoring_alerts" + where, Long.class, args.toArray());
    long totalVal = total == null ? 0L : total;
    String sql =
        "SELECT * FROM monitoring_alerts" + where + " ORDER BY triggered_at DESC LIMIT ? OFFSET ?";
    args.add(limit);
    args.add((long) (page - 1) * limit);
    List<MonitoringAlert> rows = jdbc.query(sql, ROW, args.toArray());
    return new Page(rows, totalVal);
  }

  @Override
  public int purgeOlderThan(Instant cutoff) {
    return jdbc.update(
        "DELETE FROM monitoring_alerts WHERE triggered_at < ?", Timestamp.from(cutoff));
  }
}
