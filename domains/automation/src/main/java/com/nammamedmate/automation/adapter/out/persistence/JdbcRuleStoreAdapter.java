package com.nammamedmate.automation.adapter.out.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.automation.application.port.out.RuleStorePort;
import com.nammamedmate.automation.domain.ActionSpec;
import com.nammamedmate.automation.domain.AutomationRule;
import com.nammamedmate.automation.domain.ConditionSpec;
import com.nammamedmate.automation.domain.Guardrails;
import com.nammamedmate.automation.domain.RuleSnapshot;
import com.nammamedmate.automation.domain.RuleStatus;
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
public class JdbcRuleStoreAdapter implements RuleStorePort {

  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
  private static final TypeReference<List<Map<String, Object>>> LIST_MAP = new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public JdbcRuleStoreAdapter(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Override
  public Optional<AutomationRule> findById(UUID id) {
    List<AutomationRule> rows =
        jdbc.query(
            """
            SELECT r.*, t.category AS trigger_category
            FROM automation_rules r
            JOIN trigger_registry t ON t.trigger_id = r.trigger_id
            WHERE r.id = ? AND r.deleted_at IS NULL
            """,
            (rs, i) -> mapRule(rs),
            id);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<AutomationRule> findByNameIgnoreCase(String name) {
    List<AutomationRule> rows =
        jdbc.query(
            """
            SELECT r.*, t.category AS trigger_category
            FROM automation_rules r
            JOIN trigger_registry t ON t.trigger_id = r.trigger_id
            WHERE LOWER(r.name) = LOWER(?) AND r.deleted_at IS NULL
            """,
            (rs, i) -> mapRule(rs),
            name);
    return rows.stream().findFirst();
  }

  @Override
  public List<RuleSnapshot> listActiveOrSimulating() {
    return jdbc.query(
        """
        SELECT r.*, t.category AS trigger_category
        FROM automation_rules r
        JOIN trigger_registry t ON t.trigger_id = r.trigger_id
        WHERE r.deleted_at IS NULL AND r.status IN ('ACTIVE', 'SIMULATING')
        ORDER BY r.created_at
        """,
        (rs, i) -> mapRule(rs).toSnapshot());
  }

  @Override
  public long countByStatus(RuleStatus status) {
    Long n =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM automation_rules
            WHERE deleted_at IS NULL AND status = ?
            """,
            Long.class,
            status.name());
    return n == null ? 0L : n;
  }

  @Override
  public long countFiltered(String status, String triggerCategory, String search) {
    Filter f = buildFilter(status, triggerCategory, search);
    Long n = jdbc.queryForObject(f.countSql, Long.class, f.args.toArray());
    return n == null ? 0L : n;
  }

  @Override
  public List<AutomationRule> listFiltered(
      String status, String triggerCategory, String search, int offset, int limit) {
    Filter f = buildFilter(status, triggerCategory, search);
    List<Object> args = new ArrayList<>(f.args);
    args.add(limit);
    args.add(offset);
    return jdbc.query(f.listSql, args.toArray(), (rs, i) -> mapRule(rs));
  }

  @Override
  public void insert(AutomationRule rule) {
    jdbc.update(
        """
        INSERT INTO automation_rules (
          id, name, description, trigger_id, trigger_params, conditions, actions, guardrails,
          status, fire_count, last_fired_at, is_seed_rule, dedup_window_seconds,
          created_by, created_at, updated_at, deleted_at
        ) VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, NULL)
        """,
        rule.id(),
        rule.name(),
        rule.description(),
        rule.triggerId(),
        json(rule.triggerParams()),
        json(conditionsJson(rule.conditions())),
        json(actionsJson(rule.actions())),
        json(rule.guardrails().toMap()),
        rule.status().name(),
        rule.fireCount(),
        rule.lastFiredAt() == null ? null : Timestamp.from(rule.lastFiredAt()),
        rule.seedRule(),
        rule.dedupWindowSeconds(),
        rule.createdBy(),
        Timestamp.from(rule.createdAt()),
        Timestamp.from(rule.updatedAt()));
  }

  @Override
  public void update(AutomationRule rule) {
    jdbc.update(
        """
        UPDATE automation_rules SET
          name = ?, description = ?, trigger_id = ?, trigger_params = ?::jsonb,
          conditions = ?::jsonb, actions = ?::jsonb, guardrails = ?::jsonb,
          status = ?, fire_count = ?, last_fired_at = ?, is_seed_rule = ?,
          dedup_window_seconds = ?, updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        rule.name(),
        rule.description(),
        rule.triggerId(),
        json(rule.triggerParams()),
        json(conditionsJson(rule.conditions())),
        json(actionsJson(rule.actions())),
        json(rule.guardrails().toMap()),
        rule.status().name(),
        rule.fireCount(),
        rule.lastFiredAt() == null ? null : Timestamp.from(rule.lastFiredAt()),
        rule.seedRule(),
        rule.dedupWindowSeconds(),
        Timestamp.from(rule.updatedAt()),
        rule.id());
  }

  @Override
  public void softDelete(UUID id, Instant deletedAt) {
    jdbc.update(
        """
        UPDATE automation_rules SET deleted_at = ?, updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        Timestamp.from(deletedAt),
        Timestamp.from(deletedAt),
        id);
  }

  @Override
  public void recordFire(UUID id, Instant firedAt) {
    jdbc.update(
        """
        UPDATE automation_rules
        SET fire_count = fire_count + 1, last_fired_at = ?, updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        Timestamp.from(firedAt),
        Timestamp.from(firedAt),
        id);
  }

  @Override
  public void markSimulatingStarted(UUID id, Instant startedAt) {
    jdbc.update(
        """
        UPDATE automation_rules
        SET simulating_started_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        Timestamp.from(startedAt),
        id);
  }

  @Override
  public void clearSimulatingStarted(UUID id) {
    jdbc.update(
        """
        UPDATE automation_rules
        SET simulating_started_at = NULL
        WHERE id = ? AND deleted_at IS NULL
        """,
        id);
  }

  @Override
  public List<UUID> listSimulatingStartedBefore(Instant cutoff, int limit) {
    return jdbc.query(
        """
        SELECT id FROM automation_rules
        WHERE deleted_at IS NULL
          AND status = 'SIMULATING'
          AND simulating_started_at IS NOT NULL
          AND simulating_started_at < ?
        ORDER BY simulating_started_at
        LIMIT ?
        """,
        (rs, i) -> (UUID) rs.getObject("id"),
        Timestamp.from(cutoff),
        limit);
  }

  private Filter buildFilter(String status, String triggerCategory, String search) {
    StringBuilder where = new StringBuilder(" WHERE r.deleted_at IS NULL ");
    List<Object> args = new ArrayList<>();
    if (status != null && !status.isBlank()) {
      where.append(" AND r.status = ? ");
      args.add(status.trim().toUpperCase());
    }
    if (triggerCategory != null && !triggerCategory.isBlank()) {
      where.append(" AND t.category = ? ");
      args.add(triggerCategory.trim().toUpperCase());
    }
    if (search != null && !search.isBlank()) {
      where.append(" AND LOWER(r.name) LIKE ? ");
      args.add("%" + search.trim().toLowerCase() + "%");
    }
    String from =
        """
        FROM automation_rules r
        JOIN trigger_registry t ON t.trigger_id = r.trigger_id
        """;
    String countSql = "SELECT COUNT(*) " + from + where;
    String listSql =
        "SELECT r.*, t.category AS trigger_category "
            + from
            + where
            + " ORDER BY r.created_at DESC LIMIT ? OFFSET ? ";
    return new Filter(countSql, listSql, args);
  }

  private AutomationRule mapRule(ResultSet rs) throws SQLException {
    Timestamp last = rs.getTimestamp("last_fired_at");
    Timestamp created = rs.getTimestamp("created_at");
    Timestamp updated = rs.getTimestamp("updated_at");
    Object createdBy = rs.getObject("created_by");
    return new AutomationRule(
        (UUID) rs.getObject("id"),
        rs.getString("name"),
        rs.getString("description"),
        rs.getString("trigger_id"),
        rs.getString("trigger_category"),
        readMap(rs.getString("trigger_params")),
        readConditions(rs.getString("conditions")),
        readActions(rs.getString("actions")),
        Guardrails.fromMap(readMap(rs.getString("guardrails"))),
        RuleStatus.parse(rs.getString("status")),
        rs.getInt("fire_count"),
        last == null ? null : last.toInstant(),
        rs.getBoolean("is_seed_rule"),
        rs.getInt("dedup_window_seconds"),
        createdBy == null ? null : (UUID) createdBy,
        created == null ? Instant.EPOCH : created.toInstant(),
        updated == null ? Instant.EPOCH : updated.toInstant());
  }

  private List<Map<String, Object>> conditionsJson(List<ConditionSpec> conditions) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (ConditionSpec c : conditions) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("field", c.field());
      row.put("operator", c.operator());
      row.put("value", c.value());
      out.add(row);
    }
    return out;
  }

  private List<Map<String, Object>> actionsJson(List<ActionSpec> actions) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (ActionSpec a : actions) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("action_id", a.actionId());
      row.put("params", a.params());
      row.put("parallel", a.parallel());
      out.add(row);
    }
    return out;
  }

  private List<ConditionSpec> readConditions(String json) {
    List<Map<String, Object>> rows = readListMap(json);
    List<ConditionSpec> out = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      out.add(
          new ConditionSpec(
              Objects.toString(row.get("field"), null),
              Objects.toString(row.get("operator"), null),
              row.get("value")));
    }
    return out;
  }

  private List<ActionSpec> readActions(String json) {
    List<Map<String, Object>> rows = readListMap(json);
    List<ActionSpec> out = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      Object params = row.get("params");
      Map<String, Object> p = params instanceof Map<?, ?> m ? castMap(m) : Map.of();
      out.add(
          new ActionSpec(
              Objects.toString(row.get("action_id"), null),
              p,
              Boolean.TRUE.equals(row.get("parallel"))
                  || "true".equalsIgnoreCase(String.valueOf(row.get("parallel")))));
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> castMap(Map<?, ?> m) {
    return (Map<String, Object>) m;
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
    if (json == null) {
      return List.of();
    }
    if (json.isBlank()) {
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

  private record Filter(String countSql, String listSql, List<Object> args) {}
}
