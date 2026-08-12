package com.nammamedmate.observability_ops.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.observability_ops.application.port.out.RemediationPlaybookStore;
import com.nammamedmate.observability_ops.domain.AlertType;
import com.nammamedmate.observability_ops.domain.RemediationActionType;
import com.nammamedmate.observability_ops.domain.RemediationPlaybook;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

@Component
public class JdbcRemediationPlaybookStore implements RemediationPlaybookStore {

  private final JdbcTemplate jdbc;
  private final ObjectMapper om;
  private final RowMapper<RemediationPlaybook> row;

  public JdbcRemediationPlaybookStore(JdbcTemplate jdbc, ObjectMapper om) {
    this.jdbc = jdbc;
    this.om = om;
    this.row =
        (rs, i) ->
            new RemediationPlaybook(
                (UUID) rs.getObject("id"),
                AlertType.valueOf(rs.getString("alert_type")),
                RemediationActionType.valueOf(rs.getString("auto_remediation_action")),
                rs.getString("description"),
                readMap(rs.getString("threshold")),
                rs.getBoolean("is_enabled"),
                Optional.ofNullable(rs.getTimestamp("last_triggered_at"))
                    .map(Timestamp::toInstant)
                    .orElse(null),
                (UUID) rs.getObject("updated_by"),
                rs.getTimestamp("updated_at").toInstant());
  }

  @Override
  public List<RemediationPlaybook> findAll() {
    return jdbc.query("SELECT * FROM remediation_playbooks ORDER BY alert_type", row);
  }

  @Override
  public Optional<RemediationPlaybook> findById(UUID id) {
    List<RemediationPlaybook> rows =
        jdbc.query("SELECT * FROM remediation_playbooks WHERE id = ?", row, id);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<RemediationPlaybook> findByAlertType(AlertType alertType) {
    List<RemediationPlaybook> rows =
        jdbc.query(
            "SELECT * FROM remediation_playbooks WHERE alert_type = ?", row, alertType.name());
    return rows.stream().findFirst();
  }

  @Override
  public RemediationPlaybook update(
      UUID id, boolean enabled, Map<String, Object> threshold, UUID updatedBy, Instant updatedAt) {
    jdbc.update(
        """
        UPDATE remediation_playbooks
        SET is_enabled = ?, threshold = ?::jsonb, updated_by = ?, updated_at = ?
        WHERE id = ?
        """,
        enabled,
        write(threshold),
        updatedBy,
        Timestamp.from(updatedAt),
        id);
    return findById(id).orElseThrow();
  }

  @Override
  public void touchLastTriggered(UUID id, Instant at) {
    jdbc.update(
        "UPDATE remediation_playbooks SET last_triggered_at = ? WHERE id = ?",
        Timestamp.from(at),
        id);
  }

  private Map<String, Object> readMap(String json) {
    if (json == null) {
      return Map.of();
    }
    if (json.isBlank()) {
      return Map.of();
    }
    try {
      return om.readValue(json, new TypeReference<>() {});
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private String write(Map<String, Object> map) {
    try {
      return om.writeValueAsString(map == null ? Map.of() : map);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }
}
