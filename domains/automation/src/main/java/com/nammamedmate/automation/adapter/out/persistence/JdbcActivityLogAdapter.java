package com.nammamedmate.automation.adapter.out.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.automation.application.port.out.ActivityLogPort;
import com.nammamedmate.automation.domain.ActivityLogEntry;
import com.nammamedmate.automation.domain.ActivityStats;
import com.nammamedmate.automation.domain.ActivityStatus;
import com.nammamedmate.automation.domain.RuleHealthMetrics;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcActivityLogAdapter implements ActivityLogPort {

  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
  private static final TypeReference<List<Map<String, Object>>> LIST_MAP = new TypeReference<>() {};

  private static final String SELECT =
      """
      SELECT a.id, a.rule_id, r.name AS rule_name, a.workflow_execution_id, a.trigger_event_id,
             COALESCE(te.trigger_id, r.trigger_id) AS trigger_event,
             te.payload::text AS trigger_payload, te.fired_at AS trigger_fired_at,
             a.entity_type, a.entity_id, a.entity_name, a.action_type,
             a.action_params::text AS action_params,
             a.conditions_evaluated::text AS conditions_evaluated,
             a.before_state::text AS before_state, a.after_state::text AS after_state,
             a.status, a.actor, a.override_by, a.triggered_at, a.executed_at, a.execution_ms,
             a.references_action_id, a.error_message, a.created_at,
             EXISTS (
               SELECT 1 FROM automation_activity_log rb
               WHERE rb.references_action_id = a.id AND rb.action_type = 'ROLLBACK'
             ) AS rolled_back,
             (SELECT rb.id FROM automation_activity_log rb
              WHERE rb.references_action_id = a.id AND rb.action_type = 'ROLLBACK'
              LIMIT 1) AS rollback_action_id
      FROM automation_activity_log a
      LEFT JOIN automation_rules r ON r.id = a.rule_id
      LEFT JOIN trigger_events te ON te.id = a.trigger_event_id
      LEFT JOIN trigger_registry tr ON tr.trigger_id = te.trigger_id
      LEFT JOIN trigger_registry tr2 ON tr2.trigger_id = r.trigger_id
      """;

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public JdbcActivityLogAdapter(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Override
  public UUID append(String actionType, String status, String message, Map<String, Object> detail) {
    ActivityLogEntry entry = ActivityLogEntry.fromAppend(actionType, status, message, detail);
    UUID id = entry.id();
    jdbc.update(
        """
        INSERT INTO automation_activity_log (
          id, rule_id, workflow_execution_id, trigger_event_id, entity_type, entity_id, entity_name,
          action_type, action_params, conditions_evaluated, before_state, after_state,
          status, actor, override_by, triggered_at, executed_at, execution_ms,
          references_action_id, error_message, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb,
                  ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        id,
        entry.ruleId(),
        entry.workflowExecutionId(),
        entry.triggerEventId(),
        entry.entityType(),
        entry.entityId(),
        entry.entityName(),
        entry.actionType(),
        json(entry.actionParams()),
        json(entry.conditionsEvaluated()),
        json(entry.beforeState()),
        json(entry.afterState()),
        entry.status().name(),
        entry.actor(),
        entry.overrideBy(),
        Timestamp.from(entry.triggeredAt()),
        entry.executedAt() == null ? null : Timestamp.from(entry.executedAt()),
        entry.executionMs(),
        entry.referencesActionId(),
        entry.errorMessage(),
        Timestamp.from(entry.createdAt()));
    return id;
  }

  @Override
  public Optional<ActivityLogEntry> findById(UUID id) {
    List<ActivityLogEntry> rows = jdbc.query(SELECT + " WHERE a.id = ?", (rs, i) -> mapRow(rs), id);
    return rows.stream().findFirst();
  }

  @Override
  public boolean existsRollbackFor(UUID originalId) {
    Boolean found =
        jdbc.queryForObject(
            """
            SELECT EXISTS (
              SELECT 1 FROM automation_activity_log
              WHERE references_action_id = ? AND action_type = 'ROLLBACK'
            )
            """,
            Boolean.class,
            originalId);
    return Boolean.TRUE.equals(found);
  }

  @Override
  public List<ActivityLogEntry> list(ActivityQuery query, int offset, int limit) {
    Filter f = buildFilter(query);
    List<Object> args = new ArrayList<>(f.args);
    args.add(limit);
    args.add(offset);
    return jdbc.query(
        SELECT + f.where + " ORDER BY a.triggered_at DESC LIMIT ? OFFSET ?",
        args.toArray(),
        (rs, i) -> mapRow(rs));
  }

  @Override
  public long count(ActivityQuery query) {
    Filter f = buildFilter(query);
    Long n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM (" + SELECT + f.where + ") q", Long.class, f.args.toArray());
    return n == null ? 0L : n;
  }

  @Override
  public ActivityStats stats(Instant now) {
    Instant dayAgo = now.minusSeconds(24L * 3600L);
    Instant weekAgo = now.minusSeconds(7L * 24L * 3600L);
    Instant monthAgo = now.minusSeconds(30L * 24L * 3600L);
    return jdbc.query(
        """
            SELECT
              COUNT(*) FILTER (WHERE triggered_at >= ?) AS last_24h,
              COUNT(*) FILTER (WHERE triggered_at >= ?) AS this_week,
              COUNT(*) FILTER (WHERE status = 'EXECUTED' AND actor = 'AUTOMATION' AND triggered_at >= ?)
                AS saved_30d,
              COUNT(*) FILTER (WHERE status = 'EXCEPTION' AND triggered_at >= ?) AS exceptions_24h,
              COUNT(*) FILTER (WHERE status = 'PENDING_APPROVAL') AS pending,
              MAX(triggered_at) AS last_action_at
            FROM automation_activity_log
            """,
        rs -> {
          rs.next();
          Timestamp last = rs.getTimestamp("last_action_at");
          return new ActivityStats(
              rs.getLong("last_24h"),
              rs.getLong("this_week"),
              rs.getLong("saved_30d"),
              rs.getLong("exceptions_24h"),
              rs.getLong("pending"),
              last == null ? null : last.toInstant());
        },
        Timestamp.from(dayAgo),
        Timestamp.from(weekAgo),
        Timestamp.from(monthAgo),
        Timestamp.from(dayAgo));
  }

  @Override
  public List<RuleHealthMetrics> perRuleHealth(Instant since) {
    Timestamp from = Timestamp.from(since);
    return jdbc.query(
        """
        SELECT r.id AS rule_id, r.name, r.status,
               COALESCE(m.fire_count, 0) AS fire_count,
               COALESCE(m.executed, 0) AS executed,
               COALESCE(m.exceptions, 0) AS exceptions,
               m.avg_ms, m.last_fired,
               e.error_message AS last_error,
               e.triggered_at AS last_error_at
        FROM automation_rules r
        LEFT JOIN (
          SELECT rule_id,
            COUNT(*) FILTER (WHERE status IN ('EXECUTED', 'EXCEPTION')) AS fire_count,
            COUNT(*) FILTER (WHERE status = 'EXECUTED') AS executed,
            COUNT(*) FILTER (WHERE status = 'EXCEPTION') AS exceptions,
            AVG(execution_ms) FILTER (WHERE execution_ms IS NOT NULL) AS avg_ms,
            MAX(triggered_at) FILTER (WHERE status IN ('EXECUTED', 'EXCEPTION')) AS last_fired
          FROM automation_activity_log
          WHERE triggered_at >= ? AND rule_id IS NOT NULL
          GROUP BY rule_id
        ) m ON m.rule_id = r.id
        LEFT JOIN LATERAL (
          SELECT error_message, triggered_at
          FROM automation_activity_log
          WHERE rule_id = r.id AND status = 'EXCEPTION' AND triggered_at >= ?
          ORDER BY triggered_at DESC
          LIMIT 1
        ) e ON TRUE
        WHERE r.deleted_at IS NULL
        ORDER BY r.name
        """,
        (rs, i) -> {
          Timestamp lastFired = rs.getTimestamp("last_fired");
          Timestamp lastErr = rs.getTimestamp("last_error_at");
          Object avg = rs.getObject("avg_ms");
          Integer avgMs = avg == null ? null : (int) Math.round(((Number) avg).doubleValue());
          return new RuleHealthMetrics(
              (UUID) rs.getObject("rule_id"),
              rs.getString("name"),
              rs.getString("status"),
              rs.getLong("fire_count"),
              rs.getLong("executed"),
              rs.getLong("exceptions"),
              rs.getString("last_error"),
              lastErr == null ? null : lastErr.toInstant(),
              avgMs,
              lastFired == null ? null : lastFired.toInstant());
        },
        from,
        from);
  }

  private Filter buildFilter(ActivityQuery query) {
    StringBuilder where = new StringBuilder(" WHERE 1=1 ");
    List<Object> args = new ArrayList<>();
    if (query != null) {
      if (query.status() != null && !query.status().isBlank()) {
        where.append(" AND a.status = ? ");
        args.add(query.status());
      }
      if (query.ruleId() != null) {
        where.append(" AND a.rule_id = ? ");
        args.add(query.ruleId());
      }
      if (query.triggerCategory() != null && !query.triggerCategory().isBlank()) {
        where.append(" AND COALESCE(tr.category, tr2.category) = ? ");
        args.add(query.triggerCategory().trim().toUpperCase(Locale.ROOT));
      }
      if (query.entityType() != null && !query.entityType().isBlank()) {
        where.append(" AND a.entity_type = ? ");
        args.add(query.entityType().trim().toUpperCase(Locale.ROOT));
      }
      if (query.dateFrom() != null) {
        where.append(" AND a.triggered_at >= ? ");
        args.add(Timestamp.from(query.dateFrom()));
      }
      if (query.dateTo() != null) {
        where.append(" AND a.triggered_at <= ? ");
        args.add(Timestamp.from(query.dateTo()));
      }
      Set<String> types = query.actionTypesOnly();
      if (types != null && !types.isEmpty()) {
        where.append(" AND (a.action_type IN (");
        int i = 0;
        for (String t : types) {
          if (i++ > 0) {
            where.append(',');
          }
          where.append('?');
          args.add(t);
        }
        where.append(
            ") OR (a.action_type = 'ROLLBACK' AND a.action_params->>'rolled_back_action' IN (");
        i = 0;
        for (String t : types) {
          if (i++ > 0) {
            where.append(',');
          }
          where.append('?');
          args.add(t);
        }
        where.append("))) ");
      }
    }
    return new Filter(where.toString(), args);
  }

  private ActivityLogEntry mapRow(ResultSet rs) throws SQLException {
    Timestamp triggered = rs.getTimestamp("triggered_at");
    Timestamp executed = rs.getTimestamp("executed_at");
    Timestamp created = rs.getTimestamp("created_at");
    Timestamp fired = rs.getTimestamp("trigger_fired_at");
    Integer ms = rs.getObject("execution_ms") == null ? null : rs.getInt("execution_ms");
    return new ActivityLogEntry(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("rule_id"),
        rs.getString("rule_name"),
        (UUID) rs.getObject("workflow_execution_id"),
        (UUID) rs.getObject("trigger_event_id"),
        rs.getString("trigger_event"),
        readMap(rs.getString("trigger_payload")),
        fired == null ? null : fired.toInstant(),
        rs.getString("entity_type"),
        (UUID) rs.getObject("entity_id"),
        rs.getString("entity_name"),
        rs.getString("action_type"),
        readMap(rs.getString("action_params")),
        readList(rs.getString("conditions_evaluated")),
        readMapOrNull(rs.getString("before_state")),
        readMapOrNull(rs.getString("after_state")),
        ActivityStatus.fromLog(rs.getString("status")),
        rs.getString("actor"),
        (UUID) rs.getObject("override_by"),
        triggered == null ? Instant.EPOCH : triggered.toInstant(),
        executed == null ? null : executed.toInstant(),
        ms,
        (UUID) rs.getObject("references_action_id"),
        rs.getString("error_message"),
        created == null ? Instant.EPOCH : created.toInstant(),
        rs.getBoolean("rolled_back"),
        (UUID) rs.getObject("rollback_action_id"));
  }

  private Map<String, Object> readMap(String json) {
    Map<String, Object> m = readMapOrNull(json);
    return m == null ? Map.of() : m;
  }

  private Map<String, Object> readMapOrNull(String json) {
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readValue(json, MAP_TYPE);
    } catch (Exception ex) {
      return Map.of();
    }
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
      return objectMapper.writeValueAsString(value == null ? Map.of() : value);
    } catch (Exception ex) {
      return "{}";
    }
  }

  private record Filter(String where, List<Object> args) {}
}
