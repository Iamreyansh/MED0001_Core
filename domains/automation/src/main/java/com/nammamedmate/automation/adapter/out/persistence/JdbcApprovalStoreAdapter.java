package com.nammamedmate.automation.adapter.out.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.automation.application.port.out.ApprovalStorePort;
import com.nammamedmate.automation.domain.ApprovalCategory;
import com.nammamedmate.automation.domain.ApprovalStatus;
import com.nammamedmate.automation.domain.ApprovalUrgency;
import com.nammamedmate.automation.domain.AutomationApproval;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcApprovalStoreAdapter implements ApprovalStorePort {

  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
  private static final TypeReference<List<Map<String, Object>>> LIST_MAP = new TypeReference<>() {};

  private static final String SELECT =
      """
      SELECT id, rule_id, rule_name, trigger_event_id, trigger_event, action_type,
             action_params::text AS action_params, entity_type, entity_id, entity_name,
             amount_paise, category, urgency, why_requires_approval,
             trigger_context::text AS trigger_context, conditions_met::text AS conditions_met,
             estimated_impact, on_reject_action, status, approved_by, rejected_by,
             approval_notes, rejection_reason, activity_log_id, triggered_at, expires_at,
             resolved_at
      FROM automation_approvals
      """;

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public JdbcApprovalStoreAdapter(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Override
  public void insert(AutomationApproval a) {
    jdbc.update(
        """
        INSERT INTO automation_approvals (
          id, rule_id, rule_name, trigger_event_id, trigger_event, action_type, action_params,
          entity_type, entity_id, entity_name, amount_paise, category, urgency,
          why_requires_approval, trigger_context, conditions_met, estimated_impact,
          on_reject_action, status, activity_log_id, triggered_at, expires_at, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?,
                  ?, ?, ?, ?)
        """,
        a.id(),
        a.ruleId(),
        a.ruleName(),
        a.triggerEventId(),
        a.triggerEvent(),
        a.actionType(),
        json(a.actionParams()),
        a.entityType(),
        a.entityId(),
        a.entityName(),
        a.amountPaise(),
        a.category().name(),
        a.urgency().name(),
        a.whyRequiresApproval(),
        json(a.triggerContext()),
        json(a.conditionsMet()),
        a.estimatedImpact(),
        a.onRejectAction(),
        a.status().name(),
        a.activityLogId(),
        Timestamp.from(a.triggeredAt()),
        Timestamp.from(a.expiresAt()),
        Timestamp.from(a.triggeredAt()));
  }

  @Override
  public Optional<AutomationApproval> findById(UUID id) {
    List<AutomationApproval> rows =
        jdbc.query(SELECT + " WHERE id = ?", new Object[] {id}, (rs, i) -> mapRow(rs));
    return rows.stream().findFirst();
  }

  @Override
  public Optional<AutomationApproval> findPending(UUID ruleId, UUID entityId, String actionType) {
    List<AutomationApproval> rows =
        jdbc.query(
            SELECT
                + " WHERE rule_id = ? AND entity_id = ? AND action_type = ? AND status = 'PENDING'",
            new Object[] {ruleId, entityId, actionType},
            (rs, i) -> mapRow(rs));
    return rows.stream().findFirst();
  }

  @Override
  public List<AutomationApproval> list(
      ApprovalStatus status, ApprovalUrgency urgency, int offset, int limit) {
    Filter f = filter(status, urgency);
    List<Object> args = new ArrayList<>(f.args);
    args.add(limit);
    args.add(offset);
    return jdbc.query(
        SELECT + f.where + " ORDER BY triggered_at DESC LIMIT ? OFFSET ?",
        args.toArray(),
        (rs, i) -> mapRow(rs));
  }

  @Override
  public long count(ApprovalStatus status, ApprovalUrgency urgency) {
    Filter f = filter(status, urgency);
    Long n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM automation_approvals" + f.where, Long.class, f.args.toArray());
    return n == null ? 0L : n;
  }

  @Override
  public long countPending() {
    Long n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM automation_approvals WHERE status = 'PENDING'", Long.class);
    return n == null ? 0L : n;
  }

  @Override
  public Chips chips(Instant now) {
    Instant startOfDay =
        LocalDate.ofInstant(now, ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
    return jdbc.query(
        """
        SELECT
          COUNT(*) FILTER (WHERE status = 'PENDING') AS pending_count,
          COUNT(*) FILTER (WHERE status = 'PENDING' AND urgency = 'URGENT') AS urgent_count,
          COUNT(*) FILTER (WHERE status = 'APPROVED' AND resolved_at >= ?) AS approved_today,
          COUNT(*) FILTER (WHERE status = 'REJECTED' AND resolved_at >= ?) AS rejected_today
        FROM automation_approvals
        """,
        rs -> {
          rs.next();
          return new Chips(
              rs.getLong("pending_count"),
              rs.getLong("urgent_count"),
              rs.getLong("approved_today"),
              rs.getLong("rejected_today"));
        },
        Timestamp.from(startOfDay),
        Timestamp.from(startOfDay));
  }

  @Override
  public ApprovalQueueStats stats(Instant now) {
    Instant from = now.minusSeconds(7L * 24L * 3600L);
    Map<String, Object> rates =
        jdbc.query(
            """
            SELECT
              COALESCE(AVG(EXTRACT(EPOCH FROM (resolved_at - triggered_at)) / 60.0)
                FILTER (WHERE status IN ('APPROVED', 'REJECTED') AND resolved_at >= ?), 0)
                AS avg_minutes,
              COUNT(*) FILTER (WHERE status = 'APPROVED' AND resolved_at >= ?) AS approved,
              COUNT(*) FILTER (WHERE status = 'REJECTED' AND resolved_at >= ?) AS rejected,
              COUNT(*) FILTER (WHERE status = 'EXPIRED' AND resolved_at >= ?) AS expired
            FROM automation_approvals
            """,
            rs -> {
              rs.next();
              Map<String, Object> m = new LinkedHashMap<>();
              m.put("avg", rs.getDouble("avg_minutes"));
              m.put("approved", rs.getLong("approved"));
              m.put("rejected", rs.getLong("rejected"));
              m.put("expired", rs.getLong("expired"));
              return m;
            },
            Timestamp.from(from),
            Timestamp.from(from),
            Timestamp.from(from),
            Timestamp.from(from));
    if (rates == null) {
      rates = Map.of("avg", 0.0, "approved", 0L, "rejected", 0L, "expired", 0L);
    }
    long approved = ((Number) rates.get("approved")).longValue();
    long rejected = ((Number) rates.get("rejected")).longValue();
    long expired = ((Number) rates.get("expired")).longValue();
    long decided = approved + rejected;
    long all = decided + expired;
    double approvalPct = decided == 0 ? 0.0 : round1(approved * 100.0 / decided);
    double rejectionPct = decided == 0 ? 0.0 : round1(rejected * 100.0 / decided);
    double expiryPct = all == 0 ? 0.0 : round1(expired * 100.0 / all);
    List<Map<String, Object>> top =
        jdbc.query(
            """
            SELECT category, COUNT(*) AS cnt
            FROM automation_approvals
            WHERE status = 'PENDING'
            GROUP BY category
            ORDER BY cnt DESC, category
            """,
            (rs, i) -> {
              Map<String, Object> row = new LinkedHashMap<>();
              row.put("category", rs.getString("category"));
              row.put("count", rs.getLong("cnt"));
              return row;
            });
    return new ApprovalQueueStats(
        round1(((Number) rates.get("avg")).doubleValue()),
        approvalPct,
        rejectionPct,
        expiryPct,
        top);
  }

  @Override
  public int markResolved(
      UUID id,
      ApprovalStatus expected,
      ApprovalStatus next,
      UUID actorId,
      String notes,
      String reason,
      UUID activityLogId,
      Instant resolvedAt) {
    UUID approvedBy = next == ApprovalStatus.APPROVED ? actorId : null;
    UUID rejectedBy = next == ApprovalStatus.REJECTED ? actorId : null;
    return jdbc.update(
        """
        UPDATE automation_approvals SET
          status = ?, approved_by = ?, rejected_by = ?, approval_notes = ?,
          rejection_reason = ?, activity_log_id = ?, resolved_at = ?
        WHERE id = ? AND status = ?
        """,
        next.name(),
        approvedBy,
        rejectedBy,
        notes,
        reason,
        activityLogId,
        Timestamp.from(resolvedAt),
        id,
        expected.name());
  }

  @Override
  public List<AutomationApproval> listExpired(Instant now, int limit) {
    return jdbc.query(
        SELECT + " WHERE status = 'PENDING' AND expires_at <= ? ORDER BY expires_at LIMIT ?",
        new Object[] {Timestamp.from(now), limit},
        (rs, i) -> mapRow(rs));
  }

  private Filter filter(ApprovalStatus status, ApprovalUrgency urgency) {
    StringBuilder where = new StringBuilder(" WHERE 1=1 ");
    List<Object> args = new ArrayList<>();
    if (status != null) {
      where.append(" AND status = ? ");
      args.add(status.name());
    }
    if (urgency != null) {
      where.append(" AND urgency = ? ");
      args.add(urgency.name());
    }
    return new Filter(where.toString(), args);
  }

  private AutomationApproval mapRow(ResultSet rs) throws SQLException {
    Timestamp triggered = rs.getTimestamp("triggered_at");
    Timestamp expires = rs.getTimestamp("expires_at");
    Timestamp resolved = rs.getTimestamp("resolved_at");
    Object amount = rs.getObject("amount_paise");
    return new AutomationApproval(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("rule_id"),
        rs.getString("rule_name"),
        (UUID) rs.getObject("trigger_event_id"),
        rs.getString("trigger_event"),
        rs.getString("action_type"),
        readMap(rs.getString("action_params")),
        rs.getString("entity_type"),
        (UUID) rs.getObject("entity_id"),
        rs.getString("entity_name"),
        amount == null ? null : ((Number) amount).longValue(),
        ApprovalCategory.parse(rs.getString("category")),
        parseUrgency(rs.getString("urgency")),
        rs.getString("why_requires_approval"),
        readMap(rs.getString("trigger_context")),
        readList(rs.getString("conditions_met")),
        rs.getString("estimated_impact"),
        rs.getString("on_reject_action"),
        parseStatus(rs.getString("status")),
        (UUID) rs.getObject("approved_by"),
        (UUID) rs.getObject("rejected_by"),
        rs.getString("approval_notes"),
        rs.getString("rejection_reason"),
        (UUID) rs.getObject("activity_log_id"),
        triggered == null ? Instant.EPOCH : triggered.toInstant(),
        expires == null ? Instant.EPOCH : expires.toInstant(),
        resolved == null ? null : resolved.toInstant());
  }

  private static ApprovalStatus parseStatus(String raw) {
    try {
      return ApprovalStatus.parse(raw);
    } catch (RuntimeException ex) {
      return ApprovalStatus.PENDING;
    }
  }

  private static ApprovalUrgency parseUrgency(String raw) {
    try {
      return ApprovalUrgency.parse(raw);
    } catch (RuntimeException ex) {
      return ApprovalUrgency.NORMAL;
    }
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
      return "{}";
    }
  }

  private static double round1(double v) {
    return Math.round(v * 10.0) / 10.0;
  }

  private record Filter(String where, List<Object> args) {}
}
