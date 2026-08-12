package com.nammamedmate.automation.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.automation.application.port.out.WorkflowStorePort;
import com.nammamedmate.automation.domain.AutomationWorkflow;
import com.nammamedmate.automation.domain.ConditionSpec;
import com.nammamedmate.automation.domain.StepType;
import com.nammamedmate.automation.domain.WorkflowStatus;
import com.nammamedmate.automation.domain.WorkflowStep;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcWorkflowStoreAdapter implements WorkflowStorePort {

  private static final TypeReference<List<Map<String, Object>>> LIST_MAP = new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public JdbcWorkflowStoreAdapter(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Override
  public Optional<AutomationWorkflow> findById(UUID id) {
    List<AutomationWorkflow> rows =
        jdbc.query("SELECT * FROM automation_workflows WHERE id = ?", (rs, i) -> mapRow(rs), id);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<AutomationWorkflow> findByNameIgnoreCase(String name) {
    List<AutomationWorkflow> rows =
        jdbc.query(
            "SELECT * FROM automation_workflows WHERE LOWER(name) = LOWER(?)",
            (rs, i) -> mapRow(rs),
            name);
    return rows.stream().findFirst();
  }

  @Override
  public List<AutomationWorkflow> listAll() {
    return jdbc.query(
        "SELECT * FROM automation_workflows ORDER BY created_at DESC", (rs, i) -> mapRow(rs));
  }

  @Override
  public List<AutomationWorkflow> listActiveByTrigger(String triggerId) {
    return jdbc.query(
        """
        SELECT * FROM automation_workflows
        WHERE status = 'ACTIVE' AND trigger_id = ?
        ORDER BY created_at
        """,
        (rs, i) -> mapRow(rs),
        triggerId);
  }

  @Override
  public void insert(AutomationWorkflow workflow) {
    jdbc.update(
        """
        INSERT INTO automation_workflows (
          id, name, description, trigger_id, steps, status, version,
          is_seed_workflow, created_by, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)
        """,
        workflow.id(),
        workflow.name(),
        workflow.description(),
        workflow.triggerId(),
        json(stepsJson(workflow.steps())),
        workflow.status().name(),
        workflow.version(),
        workflow.seedWorkflow(),
        workflow.createdBy(),
        Timestamp.from(workflow.createdAt()),
        Timestamp.from(workflow.updatedAt()));
  }

  @Override
  public void update(AutomationWorkflow workflow) {
    jdbc.update(
        """
        UPDATE automation_workflows SET
          name = ?, description = ?, trigger_id = ?, steps = ?::jsonb,
          status = ?, version = ?, is_seed_workflow = ?, updated_at = ?
        WHERE id = ?
        """,
        workflow.name(),
        workflow.description(),
        workflow.triggerId(),
        json(stepsJson(workflow.steps())),
        workflow.status().name(),
        workflow.version(),
        workflow.seedWorkflow(),
        Timestamp.from(workflow.updatedAt()),
        workflow.id());
  }

  @Override
  public long countByStatus(WorkflowStatus status) {
    Long n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM automation_workflows WHERE status = ?",
            Long.class,
            status.name());
    return n == null ? 0L : n;
  }

  private AutomationWorkflow mapRow(ResultSet rs) throws SQLException {
    Timestamp created = rs.getTimestamp("created_at");
    Timestamp updated = rs.getTimestamp("updated_at");
    Object createdBy = rs.getObject("created_by");
    return new AutomationWorkflow(
        (UUID) rs.getObject("id"),
        rs.getString("name"),
        rs.getString("description"),
        rs.getString("trigger_id"),
        readSteps(rs.getString("steps")),
        WorkflowStatus.parse(rs.getString("status")),
        rs.getInt("version"),
        rs.getBoolean("is_seed_workflow"),
        createdBy == null ? null : (UUID) createdBy,
        created == null ? Instant.EPOCH : created.toInstant(),
        updated == null ? Instant.EPOCH : updated.toInstant());
  }

  private List<Map<String, Object>> stepsJson(List<WorkflowStep> steps) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (WorkflowStep s : steps) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("step_id", s.stepId());
      row.put("type", s.type().name());
      row.put("action_id", s.actionId());
      row.put("params", s.params());
      row.put("wait_duration_hours", s.waitDurationHours());
      if (s.condition() != null) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("field", s.condition().field());
        c.put("operator", s.condition().operator());
        c.put("value", s.condition().value());
        row.put("condition", c);
      } else {
        row.put("condition", null);
      }
      row.put("next_step_id_on_true", s.nextStepIdOnTrue());
      row.put("next_step_id_on_false", s.nextStepIdOnFalse());
      out.add(row);
    }
    return out;
  }

  private List<WorkflowStep> readSteps(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      List<Map<String, Object>> rows = objectMapper.readValue(json, LIST_MAP);
      List<WorkflowStep> out = new ArrayList<>();
      for (Map<String, Object> row : rows) {
        out.add(mapStep(row));
      }
      return out;
    } catch (JsonProcessingException ex) {
      return List.of();
    }
  }

  @SuppressWarnings("unchecked")
  private static WorkflowStep mapStep(Map<String, Object> row) {
    Object paramsRaw = row.get("params");
    Map<String, Object> params =
        paramsRaw instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    ConditionSpec condition = null;
    Object condRaw = row.get("condition");
    if (condRaw instanceof Map<?, ?> cm) {
      Map<String, Object> c = (Map<String, Object>) cm;
      condition =
          new ConditionSpec(
              Objects.toString(c.get("field"), null),
              Objects.toString(c.get("operator"), null),
              c.get("value"));
    }
    Integer waitHours = null;
    Object wh = row.get("wait_duration_hours");
    if (wh instanceof Number n) {
      waitHours = n.intValue();
    }
    return new WorkflowStep(
        Objects.toString(row.get("step_id"), null),
        StepType.parse(Objects.toString(row.get("type"), null)),
        Objects.toString(row.get("action_id"), null),
        params,
        waitHours,
        condition,
        Objects.toString(row.get("next_step_id_on_true"), null),
        Objects.toString(row.get("next_step_id_on_false"), null));
  }

  private String json(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception ex) {
      return "[]";
    }
  }
}
