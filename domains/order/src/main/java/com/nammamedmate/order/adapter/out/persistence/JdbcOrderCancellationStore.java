package com.nammamedmate.order.adapter.out.persistence;

import com.nammamedmate.order.application.port.out.OrderCancellationStore;
import com.nammamedmate.order.domain.CancelledByType;
import com.nammamedmate.order.domain.OrderCancellation;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public class JdbcOrderCancellationStore implements OrderCancellationStore {

  private static final RowMapper<OrderCancellation> MAPPER =
      (rs, rowNum) ->
          new OrderCancellation(
              (UUID) rs.getObject("id"),
              (UUID) rs.getObject("order_id"),
              CancelledByType.valueOf(rs.getString("cancelled_by_type")),
              (UUID) rs.getObject("cancelled_by_id"),
              rs.getString("reason"),
              rs.getTimestamp("cancelled_at").toInstant());

  private final JdbcTemplate jdbc;

  public JdbcOrderCancellationStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(OrderCancellation cancellation) {
    jdbc.update(
        """
        INSERT INTO order_cancellation (
          id, order_id, cancelled_by_type, cancelled_by_id, reason, cancelled_at
        ) VALUES (?,?,?,?,?,?)
        """,
        cancellation.id(),
        cancellation.orderId(),
        cancellation.cancelledByType().name(),
        cancellation.cancelledById(),
        cancellation.reason(),
        Timestamp.from(cancellation.cancelledAt()));
  }

  @Override
  public Optional<OrderCancellation> findByOrderId(UUID orderId) {
    List<OrderCancellation> rows =
        jdbc.query("SELECT * FROM order_cancellation WHERE order_id = ?", MAPPER, orderId);
    return rows.stream().findFirst();
  }
}
