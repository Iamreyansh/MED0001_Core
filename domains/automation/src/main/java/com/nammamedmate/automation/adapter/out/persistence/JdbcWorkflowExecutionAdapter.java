package com.nammamedmate.automation.adapter.out.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.automation.application.port.out.WorkflowExecutionPort;
import com.nammamedmate.automation.domain.WorkflowExecution;
import com.nammamedmate.automation.domain.WorkflowExecutionStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcWorkflowExecutionAdapter implements WorkflowExecutionPort {

  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
  private static final TypeReference<List<Map<String, Object>>> LIST_MAP = new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public JdbcWorkflowExecutionAdapter(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Override
  public void insert(WorkflowExecution execution) {
    jdbc.update(
        """
        INSERT INTO workflow_executions (
          id, workflow_id, workflow_version, entity_type, entity_id, entity_name,
          current_step_id, status, wait_until, context, started_at, completed_at,
          last_step_executed_at, step_history
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?::jsonb)
        """,
        execution.id(),
        execution.workflowId(),
        execution.workflowVersion(),
        execution.entityType(),
        execution.entityId(),
        execution.entityName(),
        execution.currentStepId(),
        execution.status().name(),
        execution.waitUntil() == null ? null : Timestamp.from(execution.waitUntil()),
        json(execution.context()),
        Timestamp.from(execution.startedAt()),
        execution.completedAt() == null ? null : Timestamp.from(execution.completedAt()),
        execution.lastStepExecutedAt() == null
            ? null
            : Timestamp.from(execution.lastStepExecutedAt()),
        json(execution.stepHistory()));
  }

  @Override
  public void update(WorkflowExecution execution) {
    jdbc.update(
        """
        UPDATE workflow_executions SET
          current_step_id = ?, status = ?, wait_until = ?, context = ?::jsonb,
          completed_at = ?, last_step_executed_at = ?, step_history = ?::jsonb
        WHERE id = ?
        """,
        execution.currentStepId(),
        execution.status().name(),
        execution.waitUntil() == null ? null : Timestamp.from(execution.waitUntil()),
        json(execution.context()),
        execution.completedAt() == null ? null : Timestamp.from(execution.completedAt()),
        execution.lastStepExecutedAt() == null
            ? null
            : Timestamp.from(execution.lastStepExecutedAt()),
        json(execution.stepHistory()),
        execution.id());
  }

  @Override
  public Optional<WorkflowExecution> findById(UUID id) {
    List<WorkflowExecution> rows =
        jdbc.query("SELECT * FROM workflow_executions WHERE id = ?", (rs, i) -> mapRow(rs), id);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<WorkflowExecution> findRunning(UUID workflowId, UUID entityId) {
    List<WorkflowExecution> rows =
        jdbc.query(
            """
            SELECT * FROM workflow_executions
            WHERE workflow_id = ? AND entity_id = ? AND status = 'RUNNING'
            LIMIT 1
            """,
            (rs, i) -> mapRow(rs),
            workflowId,
            entityId);
    return rows.stream().findFirst();
  }

  @Override
  public long countByWorkflowAndStatus(UUID workflowId, WorkflowExecutionStatus status) {
    Long n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM workflow_executions WHERE workflow_id = ? AND status = ?",
            Long.class,
            workflowId,
            status.name());
    return n == null ? 0L : n;
  }

  @Override
  public long countCompletedSince(UUID workflowId, Instant since) {
    Long n =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM workflow_executions
            WHERE workflow_id = ? AND status = 'COMPLETED' AND completed_at >= ?
            """,
            Long.class,
            workflowId,
            Timestamp.from(since));
    return n == null ? 0L : n;
  }

  @Override
  public Double avgCompletionHours(UUID workflowId) {
    Double v =
        jdbc.queryForObject(
            """
            SELECT AVG(EXTRACT(EPOCH FROM (completed_at - started_at)) / 3600.0)
            FROM workflow_executions
            WHERE workflow_id = ? AND status = 'COMPLETED' AND completed_at IS NOT NULL
            """,
            Double.class,
            workflowId);
    return v;
  }

  @Override
  public int pauseRunning(UUID workflowId) {
    return jdbc.update(
        """
        UPDATE workflow_executions SET status = 'PAUSED', wait_until = NULL
        WHERE workflow_id = ? AND status = 'RUNNING'
        """,
        workflowId);
  }

  @Override
  public List<WorkflowExecution> list(
      UUID workflowId, WorkflowExecutionStatus status, int offset, int limit) {
    if (status == null) {
      return jdbc.query(
          """
          SELECT * FROM workflow_executions
          WHERE workflow_id = ?
          ORDER BY started_at DESC
          LIMIT ? OFFSET ?
          """,
          (rs, i) -> mapRow(rs),
          workflowId,
          limit,
          offset);
    }
    return jdbc.query(
        """
        SELECT * FROM workflow_executions
        WHERE workflow_id = ? AND status = ?
        ORDER BY started_at DESC
        LIMIT ? OFFSET ?
        """,
        (rs, i) -> mapRow(rs),
        workflowId,
        status.name(),
        limit,
        offset);
  }

  @Override
  public long count(UUID workflowId, WorkflowExecutionStatus status) {
    Long n;
    if (status == null) {
      n =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM workflow_executions WHERE workflow_id = ?",
              Long.class,
              workflowId);
    } else {
      n =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM workflow_executions WHERE workflow_id = ? AND status = ?",
              Long.class,
              workflowId,
              status.name());
    }
    return n == null ? 0L : n;
  }

  @Override
  public List<WorkflowExecution> listWaitDue(Instant now, int limit) {
    return jdbc.query(
        """
        SELECT * FROM workflow_executions
        WHERE status = 'RUNNING' AND wait_until IS NOT NULL AND wait_until <= ?
        ORDER BY wait_until
        LIMIT ?
        """,
        (rs, i) -> mapRow(rs),
        Timestamp.from(now),
        limit);
  }

  private WorkflowExecution mapRow(ResultSet rs) throws SQLException {
    Timestamp wait = rs.getTimestamp("wait_until");
    Timestamp started = rs.getTimestamp("started_at");
    Timestamp completed = rs.getTimestamp("completed_at");
    Timestamp last = rs.getTimestamp("last_step_executed_at");
    return new WorkflowExecution(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("workflow_id"),
        rs.getInt("workflow_version"),
        rs.getString("entity_type"),
        (UUID) rs.getObject("entity_id"),
        rs.getString("entity_name"),
        rs.getString("current_step_id"),
        WorkflowExecutionStatus.parse(rs.getString("status")),
        wait == null ? null : wait.toInstant(),
        readMap(rs.getString("context")),
        started == null ? Instant.EPOCH : started.toInstant(),
        completed == null ? null : completed.toInstant(),
        last == null ? null : last.toInstant(),
        readListMap(rs.getString("step_history")));
  }

  private Map<String, Object> readMap(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(json, MAP_TYPE);
    } catch (Exception ex) {
      return Map.of();
    }
  }

  private List<Map<String, Object>> readListMap(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      return objectMapper.readValue(json, LIST_MAP);
    } catch (Exception ex) {
      return List.of();
    }
  }

  private String json(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception ex) {
      return "{}";
    }
  }
}
