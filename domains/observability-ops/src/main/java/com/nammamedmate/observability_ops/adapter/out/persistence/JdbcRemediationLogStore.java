package com.nammamedmate.observability_ops.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.observability_ops.application.port.out.RemediationLogStore;
import com.nammamedmate.observability_ops.domain.RemediationActionType;
import com.nammamedmate.observability_ops.domain.RemediationLogEntry;
import com.nammamedmate.observability_ops.domain.RemediationStatus;
import com.nammamedmate.observability_ops.domain.RemediationTriggerType;
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
public class JdbcRemediationLogStore implements RemediationLogStore {

  private final JdbcTemplate jdbc;
  private final ObjectMapper om;
  private final RowMapper<RemediationLogEntry> row;

  public JdbcRemediationLogStore(JdbcTemplate jdbc, ObjectMapper om) {
    this.jdbc = jdbc;
    this.om = om;
    this.row =
        (rs, i) ->
            new RemediationLogEntry(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("alert_id"),
                (UUID) rs.getObject("playbook_id"),
                RemediationActionType.valueOf(rs.getString("action_type")),
                RemediationTriggerType.valueOf(rs.getString("trigger_type")),
                rs.getString("target_entity_type"),
                (UUID) rs.getObject("target_entity_id"),
                readMap(rs.getString("action_details")),
                RemediationStatus.valueOf(rs.getString("status")),
                (UUID) rs.getObject("triggered_by"),
                rs.getTimestamp("triggered_at").toInstant(),
                Optional.ofNullable(rs.getTimestamp("completed_at"))
                    .map(Timestamp::toInstant)
                    .orElse(null),
                rs.getString("error_message"));
  }

  @Override
  public RemediationLogEntry insert(RemediationLogEntry entry) {
    jdbc.update(
        """
        INSERT INTO monitoring_remediation_log (
          id, alert_id, playbook_id, action_type, trigger_type, target_entity_type,
          target_entity_id, action_details, status, triggered_by, triggered_at,
          completed_at, error_message)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
        """,
        entry.id(),
        entry.alertId(),
        entry.playbookId(),
        entry.actionType().name(),
        entry.triggerType().name(),
        entry.targetEntityType(),
        entry.targetEntityId(),
        write(entry.actionDetails()),
        entry.status().name(),
        entry.triggeredBy(),
        Timestamp.from(entry.triggeredAt()),
        entry.completedAt() == null ? null : Timestamp.from(entry.completedAt()),
        entry.errorMessage());
    return entry;
  }

  @Override
  public void complete(
      UUID id,
      RemediationStatus status,
      Map<String, Object> actionDetails,
      Instant completedAt,
      String errorMessage) {
    jdbc.update(
        """
        UPDATE monitoring_remediation_log
        SET status = ?, action_details = ?::jsonb, completed_at = ?, error_message = ?
        WHERE id = ?
        """,
        status.name(),
        write(actionDetails),
        Timestamp.from(completedAt),
        errorMessage,
        id);
  }

  @Override
  public Optional<Instant> lastTriggeredAt(RemediationActionType actionType, UUID targetEntityId) {
    List<Instant> rows =
        jdbc.query(
            """
            SELECT triggered_at FROM monitoring_remediation_log
            WHERE action_type = ? AND target_entity_id = ?
            ORDER BY triggered_at DESC LIMIT 1
            """,
            (rs, i) -> rs.getTimestamp("triggered_at").toInstant(),
            actionType.name(),
            targetEntityId);
    return rows.stream().findFirst();
  }

  @Override
  public int countByActionAndTargetSince(
      RemediationActionType actionType, UUID targetEntityId, Instant since) {
    Integer count =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM monitoring_remediation_log
            WHERE action_type = ? AND target_entity_id = ? AND triggered_at >= ?
            """,
            Integer.class,
            actionType.name(),
            targetEntityId,
            Timestamp.from(since));
    return count == null ? 0 : count;
  }

  @Override
  public Page list(
      RemediationActionType actionType,
      RemediationStatus status,
      Instant dateFrom,
      Instant dateTo,
      int page,
      int limit) {
    StringBuilder where = new StringBuilder(" WHERE 1=1 ");
    List<Object> args = new ArrayList<>();
    if (actionType != null) {
      where.append(" AND action_type = ? ");
      args.add(actionType.name());
    }
    if (status != null) {
      where.append(" AND status = ? ");
      args.add(status.name());
    }
    if (dateFrom != null) {
      where.append(" AND triggered_at >= ? ");
      args.add(Timestamp.from(dateFrom));
    }
    if (dateTo != null) {
      where.append(" AND triggered_at <= ? ");
      args.add(Timestamp.from(dateTo));
    }
    Long total =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM monitoring_remediation_log" + where, Long.class, args.toArray());
    long totalVal = total != null ? total : 0L;
    String sql =
        "SELECT * FROM monitoring_remediation_log"
            + where
            + " ORDER BY triggered_at DESC LIMIT ? OFFSET ?";
    args.add(limit);
    args.add((long) (page - 1) * limit);
    return new Page(jdbc.query(sql, row, args.toArray()), totalVal);
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
