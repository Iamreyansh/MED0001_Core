package com.nammamedmate.observability_ops.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.observability_ops.application.port.out.IncidentStore;
import com.nammamedmate.observability_ops.domain.AffectedService;
import com.nammamedmate.observability_ops.domain.Incident;
import com.nammamedmate.observability_ops.domain.IncidentSeverity;
import com.nammamedmate.observability_ops.domain.IncidentStatus;
import com.nammamedmate.observability_ops.domain.IncidentStatusEntry;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

@Component
public class JdbcIncidentStore implements IncidentStore {

  private final JdbcTemplate jdbc;
  private final ObjectMapper om;
  private final RowMapper<Incident> row;

  public JdbcIncidentStore(JdbcTemplate jdbc, ObjectMapper om) {
    this.jdbc = jdbc;
    this.om = om;
    this.row = (rs, i) -> mapRow(rs);
  }

  @Override
  public Optional<Incident> findById(UUID id) {
    List<Incident> rows = jdbc.query("SELECT * FROM monitoring_incidents WHERE id = ?", row, id);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<Incident> findBySourceAlertId(UUID alertId) {
    List<Incident> rows =
        jdbc.query("SELECT * FROM monitoring_incidents WHERE source_alert_id = ?", row, alertId);
    return rows.stream().findFirst();
  }

  @Override
  public Incident insert(Incident incident) {
    jdbc.update(
        """
        INSERT INTO monitoring_incidents (
          id, incident_number, title, severity, description, status,
          affected_services, impacted_metrics, impacted_gmv_paise,
          root_cause, fix_applied, prevention_steps, postmortem_filed,
          postmortem_deadline, postmortem_reminder_sent_at,
          detected_at, resolved_at, duration_minutes, created_by,
          source_alert_id, status_history)
        VALUES (
          ?, ?, ?, ?, ?, ?,
          ?::text[], ?::jsonb, ?,
          ?, ?, ?, ?,
          ?, ?,
          ?, ?, ?, ?,
          ?, ?::jsonb)
        """,
        incident.id(),
        incident.incidentNumber(),
        incident.title(),
        incident.severity().name(),
        incident.description(),
        incident.status().name(),
        toServiceNames(incident.affectedServices()),
        writeJson(incident.impactedMetrics()),
        incident.impactedGmvPaise(),
        incident.rootCause(),
        incident.fixApplied(),
        incident.preventionSteps(),
        incident.postmortemFiled(),
        ts(incident.postmortemDeadline()),
        ts(incident.postmortemReminderSentAt()),
        Timestamp.from(incident.detectedAt()),
        ts(incident.resolvedAt()),
        incident.durationMinutes(),
        incident.createdBy(),
        incident.sourceAlertId(),
        writeHistory(incident.statusHistory()));
    return incident;
  }

  @Override
  public Incident update(Incident incident) {
    jdbc.update(
        """
        UPDATE monitoring_incidents SET
          title = ?, severity = ?, description = ?, status = ?,
          affected_services = ?::text[], impacted_metrics = ?::jsonb,
          impacted_gmv_paise = ?, root_cause = ?, fix_applied = ?,
          prevention_steps = ?, postmortem_filed = ?,
          postmortem_deadline = ?, postmortem_reminder_sent_at = ?,
          resolved_at = ?, duration_minutes = ?, status_history = ?::jsonb
        WHERE id = ?
        """,
        incident.title(),
        incident.severity().name(),
        incident.description(),
        incident.status().name(),
        toServiceNames(incident.affectedServices()),
        writeJson(incident.impactedMetrics()),
        incident.impactedGmvPaise(),
        incident.rootCause(),
        incident.fixApplied(),
        incident.preventionSteps(),
        incident.postmortemFiled(),
        ts(incident.postmortemDeadline()),
        ts(incident.postmortemReminderSentAt()),
        ts(incident.resolvedAt()),
        incident.durationMinutes(),
        writeHistory(incident.statusHistory()),
        incident.id());
    return incident;
  }

  @Override
  public Page list(
      IncidentStatus status,
      IncidentSeverity severity,
      Instant dateFrom,
      Instant dateTo,
      int page,
      int limit) {
    StringBuilder where = new StringBuilder(" WHERE 1=1");
    List<Object> args = new ArrayList<>();
    if (status != null) {
      where.append(" AND status = ?");
      args.add(status.name());
    }
    if (severity != null) {
      where.append(" AND severity = ?");
      args.add(severity.name());
    }
    if (dateFrom != null) {
      where.append(" AND detected_at >= ?");
      args.add(Timestamp.from(dateFrom));
    }
    if (dateTo != null) {
      where.append(" AND detected_at <= ?");
      args.add(Timestamp.from(dateTo));
    }
    Long total =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM monitoring_incidents" + where, Long.class, args.toArray());
    String sql =
        "SELECT * FROM monitoring_incidents"
            + where
            + " ORDER BY detected_at DESC LIMIT ? OFFSET ?";
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(limit);
    pageArgs.add(Math.max(0, (page - 1) * limit));
    List<Incident> rows = jdbc.query(sql, row, pageArgs.toArray());
    return new Page(rows, total == null ? 0L : total);
  }

  @Override
  public int countP1P2Between(Instant fromInclusive, Instant toExclusive) {
    Integer n =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM monitoring_incidents
            WHERE severity IN ('P1','P2')
              AND detected_at >= ? AND detected_at < ?
            """,
            Integer.class,
            Timestamp.from(fromInclusive),
            Timestamp.from(toExclusive));
    return n == null ? 0 : n;
  }

  @Override
  public List<Incident> findResolvedAwaitingPostmortemReminder(Instant resolvedBefore) {
    return jdbc.query(
        """
        SELECT * FROM monitoring_incidents
        WHERE status = 'RESOLVED'
          AND severity IN ('P1','P2')
          AND postmortem_filed = FALSE
          AND postmortem_reminder_sent_at IS NULL
          AND resolved_at IS NOT NULL
          AND resolved_at <= ?
        """,
        row,
        Timestamp.from(resolvedBefore));
  }

  private Incident mapRow(ResultSet rs) throws SQLException {
    return new Incident(
        (UUID) rs.getObject("id"),
        rs.getString("incident_number"),
        rs.getString("title"),
        IncidentSeverity.valueOf(rs.getString("severity")),
        rs.getString("description"),
        IncidentStatus.valueOf(rs.getString("status")),
        readServices(rs),
        readMap(rs.getString("impacted_metrics")),
        rs.getLong("impacted_gmv_paise"),
        rs.getString("root_cause"),
        rs.getString("fix_applied"),
        rs.getString("prevention_steps"),
        rs.getBoolean("postmortem_filed"),
        instant(rs.getTimestamp("postmortem_deadline")),
        instant(rs.getTimestamp("postmortem_reminder_sent_at")),
        rs.getTimestamp("detected_at").toInstant(),
        instant(rs.getTimestamp("resolved_at")),
        (Integer) rs.getObject("duration_minutes"),
        (UUID) rs.getObject("created_by"),
        (UUID) rs.getObject("source_alert_id"),
        readHistory(rs.getString("status_history")));
  }

  private static List<AffectedService> readServices(ResultSet rs) throws SQLException {
    Array array = rs.getArray("affected_services");
    if (array == null) {
      return List.of();
    }
    Object raw = array.getArray();
    if (!(raw instanceof Object[] values)) {
      return List.of();
    }
    List<AffectedService> out = new ArrayList<>();
    for (Object v : values) {
      if (v != null) {
        out.add(AffectedService.valueOf(String.valueOf(v)));
      }
    }
    return out;
  }

  private List<IncidentStatusEntry> readHistory(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      List<Map<String, Object>> raw = om.readValue(json, new TypeReference<>() {});
      List<IncidentStatusEntry> out = new ArrayList<>();
      for (Map<String, Object> m : raw) {
        out.add(
            new IncidentStatusEntry(
                IncidentStatus.valueOf(String.valueOf(m.get("status"))),
                m.get("updated_by") == null ? null : String.valueOf(m.get("updated_by")),
                m.get("update_message") == null ? null : String.valueOf(m.get("update_message")),
                Instant.parse(String.valueOf(m.get("updated_at")))));
      }
      return out;
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private String writeHistory(List<IncidentStatusEntry> history) {
    List<Map<String, Object>> raw = new ArrayList<>();
    for (IncidentStatusEntry e : history) {
      raw.add(
          Map.of(
              "status",
              e.status().name(),
              "updated_by",
              e.updatedBy() == null ? "" : e.updatedBy(),
              "update_message",
              e.updateMessage() == null ? "" : e.updateMessage(),
              "updated_at",
              e.updatedAt().toString()));
    }
    return writeJson(raw);
  }

  private Map<String, Object> readMap(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    try {
      return om.readValue(json, new TypeReference<>() {});
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private String writeJson(Object value) {
    try {
      return om.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String[] toServiceNames(List<AffectedService> services) {
    return services.stream().map(Enum::name).toArray(String[]::new);
  }

  private static Timestamp ts(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  private static Instant instant(Timestamp ts) {
    return ts == null ? null : ts.toInstant();
  }
}
