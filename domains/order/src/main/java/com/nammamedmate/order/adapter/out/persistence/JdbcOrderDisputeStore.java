package com.nammamedmate.order.adapter.out.persistence;

import com.nammamedmate.order.application.port.out.OrderDisputeStore;
import com.nammamedmate.order.domain.LiableParty;
import com.nammamedmate.order.domain.OrderDispute;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public class JdbcOrderDisputeStore implements OrderDisputeStore {

  private final JdbcTemplate jdbc;

  public JdbcOrderDisputeStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public OrderDispute insert(OrderDispute dispute) {
    jdbc.update(
        """
        INSERT INTO order_dispute (
          id, order_id, reason, liable_party, flagged_by, flagged_at,
          resolved, resolved_at, resolution_notes
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        dispute.id(),
        dispute.orderId(),
        dispute.reason(),
        dispute.liableParty().name(),
        dispute.flaggedBy(),
        Timestamp.from(dispute.flaggedAt()),
        dispute.resolved(),
        dispute.resolvedAt() == null ? null : Timestamp.from(dispute.resolvedAt()),
        dispute.resolutionNotes());
    return dispute;
  }

  @Override
  public Optional<OrderDispute> findOpenByOrderId(UUID orderId) {
    List<OrderDispute> rows =
        jdbc.query(
            """
            SELECT * FROM order_dispute
            WHERE order_id = ? AND resolved = FALSE
            ORDER BY flagged_at DESC
            LIMIT 1
            """,
            this::map,
            orderId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  private OrderDispute map(ResultSet rs, int i) throws SQLException {
    Timestamp resolvedAt = rs.getTimestamp("resolved_at");
    return new OrderDispute(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("order_id"),
        rs.getString("reason"),
        LiableParty.valueOf(rs.getString("liable_party")),
        (UUID) rs.getObject("flagged_by"),
        rs.getTimestamp("flagged_at").toInstant(),
        rs.getBoolean("resolved"),
        resolvedAt == null ? null : resolvedAt.toInstant(),
        rs.getString("resolution_notes"));
  }
}
