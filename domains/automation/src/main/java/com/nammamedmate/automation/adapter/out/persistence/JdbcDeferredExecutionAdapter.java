package com.nammamedmate.automation.adapter.out.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.automation.application.port.out.DeferredExecutionPort;
import com.nammamedmate.automation.domain.DeferredExecution;
import com.nammamedmate.kernel.id.Ids;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDeferredExecutionAdapter implements DeferredExecutionPort {

  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public JdbcDeferredExecutionAdapter(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Override
  public void enqueue(
      UUID approvalId, String actionType, Map<String, Object> params, Map<String, Object> context) {
    jdbc.update(
        """
        INSERT INTO automation_deferred_executions (
          id, approval_id, action_type, action_params, execution_context, created_at
        ) VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?)
        """,
        Ids.newId(),
        approvalId,
        actionType,
        json(params),
        json(context),
        Timestamp.from(Instant.now()));
  }

  @Override
  public List<DeferredExecution> listAll() {
    return jdbc.query(
        """
        SELECT id, approval_id, action_type, action_params::text AS action_params,
               execution_context::text AS execution_context, created_at
        FROM automation_deferred_executions
        ORDER BY created_at
        """,
        (rs, i) -> {
          Timestamp created = rs.getTimestamp("created_at");
          return new DeferredExecution(
              (UUID) rs.getObject("id"),
              (UUID) rs.getObject("approval_id"),
              rs.getString("action_type"),
              readMap(rs.getString("action_params")),
              readMap(rs.getString("execution_context")),
              created == null ? Instant.EPOCH : created.toInstant());
        });
  }

  @Override
  public void delete(UUID id) {
    jdbc.update("DELETE FROM automation_deferred_executions WHERE id = ?", id);
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

  private String json(Object value) {
    try {
      return objectMapper.writeValueAsString(value == null ? Map.of() : value);
    } catch (Exception ex) {
      return "{}";
    }
  }
}
