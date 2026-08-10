package com.nammamedmate.support.adapter.out.persistence;

import com.nammamedmate.support.application.port.out.EscalationMatrixStore;
import com.nammamedmate.support.domain.EscalationRule;
import com.nammamedmate.support.domain.SlaLevel;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcEscalationMatrixStore implements EscalationMatrixStore {

  private static final String SELECT =
      """
      SELECT id, level, criteria, assigned_team, notification_channels,
             auto_escalate_after_minutes, updated_by, updated_at
      FROM support_escalation_matrix
      """;

  private final JdbcTemplate jdbc;

  public JdbcEscalationMatrixStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<EscalationRule> listAll() {
    return jdbc.query(SELECT + " ORDER BY level ASC", (rs, i) -> map(rs));
  }

  @Override
  public Optional<EscalationRule> findByLevel(SlaLevel level) {
    List<EscalationRule> rows =
        jdbc.query(SELECT + " WHERE level = ?", (rs, i) -> map(rs), level.name());
    return rows.stream().findFirst();
  }

  @Override
  public List<SlaLevel> updateRules(List<RulePatch> patches, UUID updatedBy, Instant updatedAt) {
    List<SlaLevel> updated = new ArrayList<>();
    for (RulePatch patch : patches) {
      if (patch.level() == null) {
        continue;
      }
      EscalationRule existing = findByLevel(patch.level()).orElse(null);
      if (existing == null) {
        continue;
      }
      int minutes =
          patch.autoEscalateAfterMinutes() == null
              ? existing.autoEscalateAfterMinutes()
              : patch.autoEscalateAfterMinutes();
      List<String> channels =
          patch.notificationChannels() == null
              ? existing.notificationChannels()
              : patch.notificationChannels();
      jdbc.update(
          """
          UPDATE support_escalation_matrix
          SET auto_escalate_after_minutes = ?, notification_channels = ?::text[],
              updated_by = ?, updated_at = ?
          WHERE level = ?
          """,
          minutes,
          toTextArrayLiteral(channels),
          updatedBy,
          Timestamp.from(updatedAt),
          patch.level().name());
      updated.add(patch.level());
    }
    return updated;
  }

  private static EscalationRule map(ResultSet rs) throws SQLException {
    Timestamp updated = rs.getTimestamp("updated_at");
    return new EscalationRule(
        (UUID) rs.getObject("id"),
        SlaLevel.valueOf(rs.getString("level")),
        rs.getString("criteria"),
        rs.getString("assigned_team"),
        readTextArray(rs.getArray("notification_channels")),
        rs.getInt("auto_escalate_after_minutes"),
        (UUID) rs.getObject("updated_by"),
        updated == null ? null : updated.toInstant());
  }

  private static String toTextArrayLiteral(List<String> values) {
    if (values.isEmpty()) {
      return "{}";
    }
    StringBuilder sb = new StringBuilder("{");
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      String v = values.get(i).replace("\\", "\\\\").replace("\"", "\\\"");
      sb.append('"').append(v).append('"');
    }
    sb.append('}');
    return sb.toString();
  }

  private static List<String> readTextArray(Array array) throws SQLException {
    if (array == null) {
      return List.of();
    }
    Object raw = array.getArray();
    if (raw instanceof String[] strings) {
      return List.of(strings);
    }
    if (raw instanceof Object[] objects) {
      List<String> out = new ArrayList<>(objects.length);
      for (Object o : objects) {
        if (o != null) {
          out.add(o.toString());
        }
      }
      return out;
    }
    return List.of();
  }
}
