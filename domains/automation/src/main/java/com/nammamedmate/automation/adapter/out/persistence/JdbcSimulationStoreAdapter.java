package com.nammamedmate.automation.adapter.out.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.automation.application.port.out.SimulationStorePort;
import com.nammamedmate.automation.domain.AutomationSimulation;
import com.nammamedmate.automation.domain.FalsePositiveRisk;
import com.nammamedmate.automation.domain.SimulationStatus;
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
public class JdbcSimulationStoreAdapter implements SimulationStorePort {

  private static final TypeReference<List<Map<String, Object>>> LIST_MAP = new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public JdbcSimulationStoreAdapter(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Override
  public void insert(AutomationSimulation simulation) {
    jdbc.update(
        """
        INSERT INTO automation_simulations (
          id, rule_id, sample_size, events_scanned, entities_matched, conditions_failed_count,
          false_positive_risk, risk_details, estimated_impact_summary, results_json,
          status, started_at, completed_at, triggered_by, expires_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
        """,
        simulation.id(),
        simulation.ruleId(),
        simulation.sampleSize(),
        simulation.eventsScanned(),
        simulation.entitiesMatched(),
        simulation.conditionsFailedCount(),
        simulation.falsePositiveRisk() == null ? null : simulation.falsePositiveRisk().name(),
        simulation.riskDetails(),
        simulation.estimatedImpactSummary(),
        json(simulation.actionsThatWouldFire()),
        simulation.status().name(),
        Timestamp.from(simulation.startedAt()),
        simulation.completedAt() == null ? null : Timestamp.from(simulation.completedAt()),
        simulation.triggeredBy(),
        simulation.expiresAt() == null ? null : Timestamp.from(simulation.expiresAt()));
  }

  @Override
  public Optional<AutomationSimulation> findById(UUID id) {
    List<AutomationSimulation> rows =
        jdbc.query(
            """
            SELECT * FROM automation_simulations WHERE id = ?
            """,
            (rs, i) -> mapRow(rs),
            id);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<AutomationSimulation> findByRuleAndId(UUID ruleId, UUID simulationId) {
    List<AutomationSimulation> rows =
        jdbc.query(
            """
            SELECT * FROM automation_simulations WHERE id = ? AND rule_id = ?
            """,
            (rs, i) -> mapRow(rs),
            simulationId,
            ruleId);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<AutomationSimulation> findLatestCompletedByRuleId(UUID ruleId) {
    if (ruleId == null) {
      return Optional.empty();
    }
    List<AutomationSimulation> rows =
        jdbc.query(
            """
            SELECT * FROM automation_simulations
            WHERE rule_id = ? AND status = 'COMPLETED'
            ORDER BY completed_at DESC NULLS LAST
            LIMIT 1
            """,
            (rs, i) -> mapRow(rs),
            ruleId);
    return rows.stream().findFirst();
  }

  @Override
  public List<UUID> listRunning(int limit) {
    return jdbc.query(
        """
        SELECT id FROM automation_simulations
        WHERE status = 'RUNNING'
        ORDER BY started_at
        LIMIT ?
        """,
        (rs, i) -> (UUID) rs.getObject("id"),
        limit);
  }

  @Override
  public void markCompleted(
      UUID id,
      int eventsScanned,
      int entitiesMatched,
      int conditionsFailedCount,
      FalsePositiveRisk risk,
      String riskDetails,
      String impactSummary,
      List<Map<String, Object>> actionsThatWouldFire,
      Instant completedAt,
      Instant expiresAt) {
    jdbc.update(
        """
        UPDATE automation_simulations SET
          events_scanned = ?, entities_matched = ?, conditions_failed_count = ?,
          false_positive_risk = ?, risk_details = ?, estimated_impact_summary = ?,
          results_json = ?::jsonb, status = 'COMPLETED', completed_at = ?, expires_at = ?
        WHERE id = ?
        """,
        eventsScanned,
        entitiesMatched,
        conditionsFailedCount,
        risk == null ? null : risk.name(),
        riskDetails,
        impactSummary,
        json(actionsThatWouldFire == null ? List.of() : actionsThatWouldFire),
        Timestamp.from(completedAt),
        Timestamp.from(expiresAt),
        id);
  }

  @Override
  public void markFailed(UUID id, Instant completedAt, String message) {
    jdbc.update(
        """
        UPDATE automation_simulations SET
          status = 'FAILED', completed_at = ?, risk_details = ?, expires_at = ?
        WHERE id = ?
        """,
        Timestamp.from(completedAt),
        message,
        Timestamp.from(completedAt.plusSeconds(7L * 24 * 3600)),
        id);
  }

  @Override
  public int deleteExpired(Instant now) {
    return jdbc.update(
        """
        DELETE FROM automation_simulations
        WHERE expires_at IS NOT NULL AND expires_at < ?
        """,
        Timestamp.from(now));
  }

  private AutomationSimulation mapRow(ResultSet rs) throws SQLException {
    Timestamp started = rs.getTimestamp("started_at");
    Timestamp completed = rs.getTimestamp("completed_at");
    Timestamp expires = rs.getTimestamp("expires_at");
    Object triggeredBy = rs.getObject("triggered_by");
    return new AutomationSimulation(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("rule_id"),
        rs.getInt("sample_size"),
        rs.getInt("events_scanned"),
        rs.getInt("entities_matched"),
        rs.getInt("conditions_failed_count"),
        FalsePositiveRisk.parse(rs.getString("false_positive_risk")),
        rs.getString("risk_details"),
        rs.getString("estimated_impact_summary"),
        readList(rs.getString("results_json")),
        SimulationStatus.parse(rs.getString("status")),
        started == null ? Instant.EPOCH : started.toInstant(),
        completed == null ? null : completed.toInstant(),
        triggeredBy == null ? null : (UUID) triggeredBy,
        expires == null ? null : expires.toInstant());
  }

  private List<Map<String, Object>> readList(String json) {
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
      return "[]";
    }
  }
}
