package com.nammamedmate.rider.adapter.out.persistence;

import com.nammamedmate.rider.application.port.out.RebalancingSuggestionStore;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRebalancingSuggestionStore implements RebalancingSuggestionStore {

  private final JdbcTemplate jdbc;

  public JdbcRebalancingSuggestionStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(
      UUID id,
      UUID fromZoneId,
      UUID toZoneId,
      int ridersToMove,
      String reason,
      BigDecimal confidencePct,
      String suggestedRidersJson,
      Instant expiresAt,
      Instant generatedAt) {
    jdbc.update(
        """
        INSERT INTO rebalancing_suggestions (
          id, from_zone_id, to_zone_id, riders_to_move, reason, confidence_pct,
          suggested_riders, status, expires_at, generated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, 'PENDING', ?, ?)
        """,
        id,
        fromZoneId,
        toZoneId,
        ridersToMove,
        reason,
        confidencePct,
        suggestedRidersJson,
        Timestamp.from(expiresAt),
        Timestamp.from(generatedAt));
  }

  @Override
  public List<SuggestionRow> listPending(Instant now) {
    return jdbc.query(
        """
        SELECT s.id, s.from_zone_id, fz.name AS from_zone_name,
               s.to_zone_id, tz.name AS to_zone_name,
               s.riders_to_move, s.reason, s.confidence_pct,
               s.suggested_riders::text AS suggested_riders, s.status,
               s.applied_by, s.applied_at, s.expires_at, s.generated_at
        FROM rebalancing_suggestions s
        JOIN zones fz ON fz.id = s.from_zone_id
        JOIN zones tz ON tz.id = s.to_zone_id
        WHERE s.status = 'PENDING' AND s.expires_at > ?
        ORDER BY s.confidence_pct DESC, s.generated_at DESC
        """,
        this::map,
        Timestamp.from(now));
  }

  @Override
  public Optional<SuggestionRow> findById(UUID id) {
    List<SuggestionRow> rows =
        jdbc.query(
            """
            SELECT s.id, s.from_zone_id, fz.name AS from_zone_name,
                   s.to_zone_id, tz.name AS to_zone_name,
                   s.riders_to_move, s.reason, s.confidence_pct,
                   s.suggested_riders::text AS suggested_riders, s.status,
                   s.applied_by, s.applied_at, s.expires_at, s.generated_at
            FROM rebalancing_suggestions s
            JOIN zones fz ON fz.id = s.from_zone_id
            JOIN zones tz ON tz.id = s.to_zone_id
            WHERE s.id = ?
            """,
            this::map,
            id);
    return rows.stream().findFirst();
  }

  @Override
  public boolean markApplied(UUID id, UUID appliedBy, Instant appliedAt) {
    return jdbc.update(
            """
            UPDATE rebalancing_suggestions
            SET status = 'APPLIED', applied_by = ?, applied_at = ?
            WHERE id = ? AND status = 'PENDING' AND expires_at > ?
            """,
            appliedBy,
            Timestamp.from(appliedAt),
            id,
            Timestamp.from(appliedAt))
        == 1;
  }

  @Override
  public void expireStale(Instant now) {
    jdbc.update(
        """
        UPDATE rebalancing_suggestions
        SET status = 'EXPIRED'
        WHERE status = 'PENDING' AND expires_at <= ?
        """,
        Timestamp.from(now));
  }

  private SuggestionRow map(ResultSet rs, int rowNum) throws SQLException {
    Timestamp applied = rs.getTimestamp("applied_at");
    Instant appliedAt = applied != null ? applied.toInstant() : null;
    return new SuggestionRow(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("from_zone_id"),
        rs.getString("from_zone_name"),
        (UUID) rs.getObject("to_zone_id"),
        rs.getString("to_zone_name"),
        rs.getInt("riders_to_move"),
        rs.getString("reason"),
        rs.getBigDecimal("confidence_pct"),
        rs.getString("suggested_riders"),
        rs.getString("status"),
        (UUID) rs.getObject("applied_by"),
        appliedAt,
        rs.getTimestamp("expires_at").toInstant(),
        rs.getTimestamp("generated_at").toInstant());
  }
}
