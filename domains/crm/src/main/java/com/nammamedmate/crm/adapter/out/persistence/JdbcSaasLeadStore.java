package com.nammamedmate.crm.adapter.out.persistence;

import com.nammamedmate.crm.application.port.out.SaasLeadStore;
import com.nammamedmate.crm.domain.CrmLead;
import com.nammamedmate.crm.domain.CrmLeadActivity;
import com.nammamedmate.crm.domain.LeadStage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

@Component
public class JdbcSaasLeadStore implements SaasLeadStore {

  private final JdbcTemplate jdbc;

  public JdbcSaasLeadStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static final RowMapper<CrmLead> LEAD_MAPPER = (rs, i) -> mapLead(rs);

  private static final String SELECT_LEAD =
      """
      SELECT id, pharmacy_name, contact_name, phone, email, source, stage, win_probability,
             estimated_mrr_paise, target_plan, assigned_rep_id, notes, lost_reason,
             won_at, lost_at, sales_cycle_days, linked_account_id, pharmacy_id,
             created_at, updated_at
      FROM crm_lead
      """;

  @Override
  public void insert(CrmLead lead) {
    jdbc.update(
        """
        INSERT INTO crm_lead (
          id, pharmacy_name, contact_name, phone, email, source, stage, win_probability,
          estimated_mrr_paise, target_plan, assigned_rep_id, notes, lost_reason,
          won_at, lost_at, sales_cycle_days, linked_account_id, pharmacy_id,
          deleted_at, created_at, updated_at
        ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NULL,?,?)
        """,
        lead.id(),
        lead.pharmacyName(),
        lead.contactName(),
        lead.phone(),
        lead.email(),
        lead.source(),
        lead.stage(),
        lead.winProbability(),
        lead.estimatedMrrPaise(),
        lead.targetPlan(),
        lead.assignedRepId(),
        lead.notes(),
        lead.lostReason(),
        ts(lead.wonAt()),
        ts(lead.lostAt()),
        lead.salesCycleDays(),
        lead.linkedAccountId(),
        lead.pharmacyId(),
        ts(lead.createdAt()),
        ts(lead.updatedAt()));
  }

  @Override
  public void update(CrmLead lead) {
    jdbc.update(
        """
        UPDATE crm_lead SET
          pharmacy_name = ?, contact_name = ?, phone = ?, email = ?, source = ?,
          stage = ?, win_probability = ?, estimated_mrr_paise = ?, target_plan = ?,
          assigned_rep_id = ?, notes = ?, lost_reason = ?, won_at = ?, lost_at = ?,
          sales_cycle_days = ?, linked_account_id = ?, pharmacy_id = ?, updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        lead.pharmacyName(),
        lead.contactName(),
        lead.phone(),
        lead.email(),
        lead.source(),
        lead.stage(),
        lead.winProbability(),
        lead.estimatedMrrPaise(),
        lead.targetPlan(),
        lead.assignedRepId(),
        lead.notes(),
        lead.lostReason(),
        ts(lead.wonAt()),
        ts(lead.lostAt()),
        lead.salesCycleDays(),
        lead.linkedAccountId(),
        lead.pharmacyId(),
        ts(lead.updatedAt()),
        lead.id());
  }

  @Override
  public Optional<CrmLead> findById(UUID id) {
    List<CrmLead> rows =
        jdbc.query(SELECT_LEAD + " WHERE id = ? AND deleted_at IS NULL", LEAD_MAPPER, id);
    return rows.stream().findFirst();
  }

  @Override
  public boolean existsOpenByPhone(String phone, UUID excludeLeadId) {
    Integer n =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM crm_lead
            WHERE deleted_at IS NULL AND phone = ?
              AND stage NOT IN ('WON', 'LOST')
              AND (?::uuid IS NULL OR id <> ?)
            """,
            Integer.class,
            phone,
            excludeLeadId,
            excludeLeadId);
    if (n == null) {
      return false;
    }
    return n > 0;
  }

  @Override
  public boolean existsOpenByPharmacyId(UUID pharmacyId, UUID excludeLeadId) {
    if (pharmacyId == null) {
      return false;
    }
    Integer n =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM crm_lead
            WHERE deleted_at IS NULL AND pharmacy_id = ?
              AND stage NOT IN ('WON', 'LOST')
              AND (?::uuid IS NULL OR id <> ?)
            """,
            Integer.class,
            pharmacyId,
            excludeLeadId,
            excludeLeadId);
    if (n == null) {
      return false;
    }
    return n > 0;
  }

  @Override
  public List<CrmLead> list(
      String stage, UUID repId, String source, String q, int offset, int limit) {
    Filter f = buildFilter(stage, repId, source, q);
    String sql = SELECT_LEAD + " WHERE " + f.where + " ORDER BY created_at DESC LIMIT ? OFFSET ?";
    List<Object> args = new ArrayList<>(f.args);
    args.add(limit);
    args.add(offset);
    return jdbc.query(sql, LEAD_MAPPER, args.toArray());
  }

  @Override
  public long count(String stage, UUID repId, String source, String q) {
    Filter f = buildFilter(stage, repId, source, q);
    String sql = "SELECT COUNT(*) FROM crm_lead WHERE " + f.where;
    Long n =
        f.args.isEmpty()
            ? jdbc.queryForObject(sql, Long.class)
            : jdbc.queryForObject(sql, Long.class, f.args.toArray());
    return n == null ? 0L : n;
  }

  @Override
  public PipelineChips chips(Instant periodFrom, Instant periodTo) {
    Long open =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM crm_lead
            WHERE deleted_at IS NULL AND stage NOT IN ('WON', 'LOST')
            """,
            Long.class);
    Long pipeline =
        jdbc.queryForObject(
            """
            SELECT COALESCE(SUM(estimated_mrr_paise), 0) FROM crm_lead
            WHERE deleted_at IS NULL AND stage NOT IN ('WON', 'LOST')
            """,
            Long.class);
    Long weighted =
        jdbc.queryForObject(
            """
            SELECT COALESCE(SUM(
              (COALESCE(estimated_mrr_paise, 0) * win_probability) / 100
            ), 0) FROM crm_lead
            WHERE deleted_at IS NULL AND stage NOT IN ('WON', 'LOST')
            """,
            Long.class);
    long openLeads = open == null ? 0L : open;
    long pipelinePaise = pipeline == null ? 0L : pipeline;
    long weightedPaise = weighted == null ? 0L : weighted;
    long avgDeal = openLeads == 0 ? 0L : pipelinePaise / openLeads;

    Long won =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM crm_lead
            WHERE deleted_at IS NULL AND stage = 'WON'
              AND won_at >= ? AND won_at <= ?
            """,
            Long.class,
            ts(periodFrom),
            ts(periodTo));
    Long lost =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM crm_lead
            WHERE deleted_at IS NULL AND stage = 'LOST'
              AND lost_at >= ? AND lost_at <= ?
            """,
            Long.class,
            ts(periodFrom),
            ts(periodTo));
    long wonN = won == null ? 0L : won;
    long lostN = lost == null ? 0L : lost;
    double winRate = (wonN + lostN) == 0 ? 0.0 : (wonN * 100.0) / (wonN + lostN);

    Double avgCycle =
        jdbc.queryForObject(
            """
            SELECT COALESCE(AVG(sales_cycle_days), 0) FROM crm_lead
            WHERE deleted_at IS NULL AND stage = 'WON'
              AND won_at >= ? AND won_at <= ?
              AND sales_cycle_days IS NOT NULL
            """,
            Double.class,
            ts(periodFrom),
            ts(periodTo));
    return new PipelineChips(
        openLeads,
        pipelinePaise,
        weightedPaise,
        avgDeal,
        winRate,
        avgCycle == null ? 0.0 : avgCycle);
  }

  @Override
  public Map<String, Long> openStageFunnel() {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            """
            SELECT stage, COUNT(*) AS cnt FROM crm_lead
            WHERE deleted_at IS NULL
              AND stage IN ('NEW', 'CONTACTED', 'DEMO', 'TRIAL')
            GROUP BY stage
            """);
    Map<String, Long> out = new LinkedHashMap<>();
    for (String s : List.of(LeadStage.NEW, LeadStage.CONTACTED, LeadStage.DEMO, LeadStage.TRIAL)) {
      out.put(s, 0L);
    }
    for (Map<String, Object> row : rows) {
      out.put(String.valueOf(row.get("stage")), ((Number) row.get("cnt")).longValue());
    }
    return out;
  }

  @Override
  public void insertActivity(CrmLeadActivity activity) {
    jdbc.update(
        """
        INSERT INTO crm_lead_activity (
          id, lead_id, event, stage_from, stage_to, notes, actor_id, actor_name, created_at
        ) VALUES (?,?,?,?,?,?,?,?,?)
        """,
        activity.id(),
        activity.leadId(),
        activity.event(),
        activity.stageFrom(),
        activity.stageTo(),
        activity.notes(),
        activity.actorId(),
        activity.actorName(),
        ts(activity.createdAt()));
  }

  @Override
  public List<CrmLeadActivity> listActivities(UUID leadId) {
    return jdbc.query(
        """
        SELECT id, lead_id, event, stage_from, stage_to, notes, actor_id, actor_name, created_at
        FROM crm_lead_activity
        WHERE lead_id = ?
        ORDER BY created_at ASC
        """,
        (rs, i) ->
            new CrmLeadActivity(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("lead_id"),
                rs.getString("event"),
                rs.getString("stage_from"),
                rs.getString("stage_to"),
                rs.getString("notes"),
                (UUID) rs.getObject("actor_id"),
                rs.getString("actor_name"),
                ts(rs, "created_at")),
        leadId);
  }

  @Override
  public Optional<RepRef> findActiveRep(UUID repId) {
    List<RepRef> rows =
        jdbc.query(
            """
            SELECT id, name FROM admin_staff
            WHERE id = ? AND deleted_at IS NULL AND status = 'ACTIVE'
              AND role IN ('admin_super', 'admin_operations')
            """,
            (rs, i) -> new RepRef((UUID) rs.getObject("id"), rs.getString("name")),
            repId);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<String> findRepName(UUID repId) {
    if (repId == null) {
      return Optional.empty();
    }
    List<String> rows =
        jdbc.query(
            """
            SELECT name FROM admin_staff
            WHERE id = ? AND deleted_at IS NULL
            """,
            (rs, i) -> rs.getString("name"),
            repId);
    return rows.stream().findFirst();
  }

  @Override
  public List<UUID> listActiveRepIds() {
    return jdbc.query(
        """
        SELECT id FROM admin_staff
        WHERE deleted_at IS NULL AND status = 'ACTIVE'
          AND role IN ('admin_super', 'admin_operations')
        ORDER BY created_at ASC, id ASC
        """,
        (rs, i) -> (UUID) rs.getObject("id"));
  }

  @Override
  public Optional<UUID> nextRoundRobinRepId() {
    List<UUID> reps = listActiveRepIds();
    if (reps.isEmpty()) {
      return Optional.empty();
    }
    UUID last = null;
    try {
      last =
          jdbc.queryForObject(
              "SELECT last_rep_id FROM crm_lead_rr_cursor WHERE id = 1", UUID.class);
    } catch (EmptyResultDataAccessException ignored) {
      jdbc.update(
          "INSERT INTO crm_lead_rr_cursor (id, last_rep_id, updated_at) VALUES (1, NULL, NOW())");
    }
    int idx = 0;
    if (last != null) {
      int found = reps.indexOf(last);
      idx = found < 0 ? 0 : (found + 1) % reps.size();
    }
    UUID next = reps.get(idx);
    jdbc.update(
        "UPDATE crm_lead_rr_cursor SET last_rep_id = ?, updated_at = NOW() WHERE id = 1", next);
    return Optional.of(next);
  }

  private static Filter buildFilter(String stage, UUID repId, String source, String q) {
    StringBuilder where = new StringBuilder("deleted_at IS NULL");
    List<Object> args = new ArrayList<>();
    if (stage != null) {
      where.append(" AND stage = ?");
      args.add(stage);
    }
    if (repId != null) {
      where.append(" AND assigned_rep_id = ?");
      args.add(repId);
    }
    if (source != null) {
      where.append(" AND source = ?");
      args.add(source);
    }
    String qq = q == null ? "" : q.trim();
    if (!qq.isEmpty()) {
      where.append(" AND (pharmacy_name ILIKE ? OR contact_name ILIKE ? OR phone ILIKE ?)");
      String like = "%" + qq + "%";
      args.add(like);
      args.add(like);
      args.add(like);
    }
    return new Filter(where.toString(), args);
  }

  private record Filter(String where, List<Object> args) {}

  private static CrmLead mapLead(ResultSet rs) throws SQLException {
    Long mrr =
        rs.getObject("estimated_mrr_paise") == null ? null : rs.getLong("estimated_mrr_paise");
    Integer cycle = rs.getObject("sales_cycle_days") == null ? null : rs.getInt("sales_cycle_days");
    return new CrmLead(
        (UUID) rs.getObject("id"),
        rs.getString("pharmacy_name"),
        rs.getString("contact_name"),
        rs.getString("phone"),
        rs.getString("email"),
        rs.getString("source"),
        rs.getString("stage"),
        rs.getInt("win_probability"),
        mrr,
        rs.getString("target_plan"),
        (UUID) rs.getObject("assigned_rep_id"),
        rs.getString("notes"),
        rs.getString("lost_reason"),
        ts(rs, "won_at"),
        ts(rs, "lost_at"),
        cycle,
        (UUID) rs.getObject("linked_account_id"),
        (UUID) rs.getObject("pharmacy_id"),
        ts(rs, "created_at"),
        ts(rs, "updated_at"));
  }

  private static Timestamp ts(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  private static Instant ts(ResultSet rs, String col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }
}
