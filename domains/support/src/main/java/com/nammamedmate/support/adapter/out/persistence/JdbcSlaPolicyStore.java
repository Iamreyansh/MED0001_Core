package com.nammamedmate.support.adapter.out.persistence;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.support.application.port.out.SlaPolicyStore;
import com.nammamedmate.support.domain.SlaLevel;
import com.nammamedmate.support.domain.SlaPolicy;
import com.nammamedmate.support.domain.TicketCategory;
import com.nammamedmate.support.domain.TicketPriority;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcSlaPolicyStore implements SlaPolicyStore {

  private static final String SELECT =
      """
      SELECT id, category, priority, first_response_sla_minutes, resolution_sla_minutes,
             sla_level, updated_by, updated_at, created_at
      FROM support_sla_policies
      """;

  private final JdbcTemplate jdbc;

  public JdbcSlaPolicyStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<SlaPolicy> listAll() {
    return jdbc.query(SELECT + " ORDER BY category ASC, priority ASC", (rs, i) -> map(rs));
  }

  @Override
  public Optional<SlaPolicy> findById(UUID id) {
    List<SlaPolicy> rows = jdbc.query(SELECT + " WHERE id = ?", (rs, i) -> map(rs), id);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<SlaPolicy> resolve(TicketCategory category, TicketPriority priority) {
    List<SlaPolicy> specific =
        jdbc.query(
            SELECT + " WHERE category = ? AND priority = ?",
            (rs, i) -> map(rs),
            category.name(),
            priority.name());
    if (!specific.isEmpty()) {
      return Optional.of(specific.getFirst());
    }
    List<SlaPolicy> any =
        jdbc.query(
            SELECT + " WHERE category = ? AND priority = 'ANY'",
            (rs, i) -> map(rs),
            category.name());
    if (!any.isEmpty()) {
      return Optional.of(any.getFirst());
    }
    List<SlaPolicy> all =
        jdbc.query(
            SELECT + " WHERE category = 'ALL' AND priority = ?",
            (rs, i) -> map(rs),
            priority.name());
    return all.stream().findFirst();
  }

  @Override
  public SlaPolicy update(
      UUID id,
      Integer firstResponseMinutes,
      Integer resolutionMinutes,
      SlaLevel slaLevel,
      UUID updatedBy,
      Instant updatedAt) {
    SlaPolicy existing =
        findById(id)
            .orElseThrow(
                () -> new AppException("SLA_POLICY_NOT_FOUND", "Policy ID does not exist", 404));
    int fr =
        firstResponseMinutes == null ? existing.firstResponseSlaMinutes() : firstResponseMinutes;
    int res = resolutionMinutes == null ? existing.resolutionSlaMinutes() : resolutionMinutes;
    SlaLevel level = slaLevel == null ? existing.slaLevel() : slaLevel;
    jdbc.update(
        """
        UPDATE support_sla_policies
        SET first_response_sla_minutes = ?, resolution_sla_minutes = ?, sla_level = ?,
            updated_by = ?, updated_at = ?
        WHERE id = ?
        """,
        fr,
        res,
        level.name(),
        updatedBy,
        Timestamp.from(updatedAt),
        id);
    return findById(id).orElseThrow();
  }

  private static SlaPolicy map(ResultSet rs) throws SQLException {
    Timestamp updated = rs.getTimestamp("updated_at");
    Timestamp created = rs.getTimestamp("created_at");
    return new SlaPolicy(
        (UUID) rs.getObject("id"),
        rs.getString("category"),
        rs.getString("priority"),
        rs.getInt("first_response_sla_minutes"),
        rs.getInt("resolution_sla_minutes"),
        SlaLevel.valueOf(rs.getString("sla_level")),
        (UUID) rs.getObject("updated_by"),
        updated == null ? null : updated.toInstant(),
        created == null ? null : created.toInstant());
  }
}
