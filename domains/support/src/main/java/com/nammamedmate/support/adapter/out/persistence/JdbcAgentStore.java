package com.nammamedmate.support.adapter.out.persistence;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.support.application.port.out.AgentStore;
import com.nammamedmate.support.domain.AgentPerformanceSnapshot;
import com.nammamedmate.support.domain.AgentProfile;
import com.nammamedmate.support.domain.TicketCategory;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcAgentStore implements AgentStore {

  private static final String PROFILE_COLS =
      "admin_user_id, specialties, is_online, max_load, display_name, updated_at";

  private final JdbcTemplate jdbc;

  public JdbcAgentStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<AgentProfile> findById(UUID adminUserId) {
    List<AgentProfile> rows =
        jdbc.query(
            "SELECT " + PROFILE_COLS + " FROM support_agent_profiles WHERE admin_user_id = ?",
            (rs, i) -> map(rs),
            adminUserId);
    return rows.stream().findFirst();
  }

  @Override
  public List<AgentProfile> listAll() {
    return jdbc.query(
        "SELECT " + PROFILE_COLS + " FROM support_agent_profiles ORDER BY display_name ASC",
        (rs, i) -> map(rs));
  }

  @Override
  public List<AgentProfile> listOnline() {
    return jdbc.query(
        "SELECT "
            + PROFILE_COLS
            + " FROM support_agent_profiles WHERE is_online = TRUE ORDER BY display_name ASC",
        (rs, i) -> map(rs));
  }

  @Override
  public List<AgentProfile> listOnlineForCategory(TicketCategory category) {
    return jdbc.query(
        """
        SELECT admin_user_id, specialties, is_online, max_load, display_name, updated_at
        FROM support_agent_profiles
        WHERE is_online = TRUE
          AND (
            cardinality(specialties) = 0
            OR ? = ANY (specialties)
          )
        """,
        (rs, i) -> map(rs),
        category.name());
  }

  @Override
  public AgentProfile updateOnline(UUID adminUserId, boolean online, Instant updatedAt) {
    int n =
        jdbc.update(
            """
            UPDATE support_agent_profiles
            SET is_online = ?, updated_at = ?
            WHERE admin_user_id = ?
            """,
            online,
            Timestamp.from(updatedAt),
            adminUserId);
    if (n == 0) {
      throw new AppException("AGENT_NOT_FOUND", "Agent ID does not exist", 404);
    }
    return findById(adminUserId).orElseThrow();
  }

  @Override
  public Optional<String> findEmail(UUID adminUserId) {
    List<String> rows =
        jdbc.query(
            """
            SELECT email FROM admin_staff
            WHERE id = ? AND deleted_at IS NULL
            """,
            (rs, i) -> rs.getString("email"),
            adminUserId);
    return rows.stream().findFirst();
  }

  @Override
  public void upsertSnapshot(AgentPerformanceSnapshot snapshot) {
    int updated =
        jdbc.update(
            """
            UPDATE support_agent_performance_snapshots
            SET tickets_handled = ?, avg_handle_minutes = ?, csat_score_avg = ?,
                sla_breach_count = ?
            WHERE agent_id = ? AND week_start = ?
            """,
            snapshot.ticketsHandled(),
            snapshot.avgHandleMinutes(),
            snapshot.csatScoreAvg(),
            snapshot.slaBreachCount(),
            snapshot.agentId(),
            Date.valueOf(snapshot.weekStart()));
    if (updated > 0) {
      return;
    }
    jdbc.update(
        """
        INSERT INTO support_agent_performance_snapshots (
          id, agent_id, week_start, tickets_handled, avg_handle_minutes,
          csat_score_avg, sla_breach_count, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        snapshot.id(),
        snapshot.agentId(),
        Date.valueOf(snapshot.weekStart()),
        snapshot.ticketsHandled(),
        snapshot.avgHandleMinutes(),
        snapshot.csatScoreAvg(),
        snapshot.slaBreachCount(),
        Timestamp.from(snapshot.createdAt()));
  }

  @Override
  public List<AgentPerformanceSnapshot> listSnapshots(UUID agentId) {
    return jdbc.query(
        """
        SELECT id, agent_id, week_start, tickets_handled, avg_handle_minutes,
               csat_score_avg, sla_breach_count, created_at
        FROM support_agent_performance_snapshots
        WHERE agent_id = ?
        ORDER BY week_start ASC
        """,
        (rs, i) -> mapSnapshot(rs),
        agentId);
  }

  @Override
  public Optional<AgentPerformanceSnapshot> findSnapshot(UUID agentId, LocalDate weekStart) {
    List<AgentPerformanceSnapshot> rows =
        jdbc.query(
            """
            SELECT id, agent_id, week_start, tickets_handled, avg_handle_minutes,
                   csat_score_avg, sla_breach_count, created_at
            FROM support_agent_performance_snapshots
            WHERE agent_id = ? AND week_start = ?
            """,
            (rs, i) -> mapSnapshot(rs),
            agentId,
            Date.valueOf(weekStart));
    return rows.stream().findFirst();
  }

  private static AgentProfile map(ResultSet rs) throws SQLException {
    return new AgentProfile(
        (UUID) rs.getObject("admin_user_id"),
        readTextArray(rs.getArray("specialties")),
        rs.getBoolean("is_online"),
        rs.getInt("max_load"),
        rs.getString("display_name"),
        rs.getTimestamp("updated_at").toInstant());
  }

  private static AgentPerformanceSnapshot mapSnapshot(ResultSet rs) throws SQLException {
    BigDecimal avg = rs.getBigDecimal("avg_handle_minutes");
    BigDecimal csat = rs.getBigDecimal("csat_score_avg");
    return new AgentPerformanceSnapshot(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("agent_id"),
        rs.getDate("week_start").toLocalDate(),
        rs.getInt("tickets_handled"),
        avg,
        csat,
        rs.getInt("sla_breach_count"),
        rs.getTimestamp("created_at").toInstant());
  }

  private static List<String> readTextArray(Array array) throws SQLException {
    if (array == null) {
      return List.of();
    }
    Object raw = array.getArray();
    if (raw instanceof String[] strings) {
      return List.of(strings);
    }
    if (raw instanceof Object[] objects) {
      List<String> out = new ArrayList<>(objects.length);
      for (Object o : objects) {
        if (o != null) {
          out.add(o.toString());
        }
      }
      return out;
    }
    return List.of();
  }
}
