package com.nammamedmate.order.adapter.out.persistence;

import com.nammamedmate.order.application.port.out.OrderStatusEventStore;
import com.nammamedmate.order.domain.ActorType;
import com.nammamedmate.order.domain.OrderStatus;
import com.nammamedmate.order.domain.OrderStatusEvent;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public class JdbcOrderStatusEventStore implements OrderStatusEventStore {

  private final JdbcTemplate jdbc;

  public JdbcOrderStatusEventStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void append(OrderStatusEvent event) {
    jdbc.update(
        """
        INSERT INTO order_status_event (
          id, order_id, from_status, to_status, actor_type, actor_id, notes, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        event.id(),
        event.orderId(),
        event.fromStatus().name(),
        event.toStatus().name(),
        event.actorType().name(),
        event.actorId(),
        event.notes(),
        Timestamp.from(event.createdAt()));
  }

  @Override
  public List<OrderStatusEvent> listByOrderId(UUID orderId) {
    return jdbc.query(
        """
        SELECT id, order_id, from_status, to_status, actor_type, actor_id, notes, created_at
        FROM order_status_event
        WHERE order_id = ?
        ORDER BY created_at ASC, id ASC
        """,
        (rs, i) ->
            new OrderStatusEvent(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("order_id"),
                OrderStatus.valueOf(rs.getString("from_status")),
                OrderStatus.valueOf(rs.getString("to_status")),
                ActorType.valueOf(rs.getString("actor_type")),
                (UUID) rs.getObject("actor_id"),
                rs.getString("notes"),
                rs.getTimestamp("created_at").toInstant()),
        orderId);
  }
}
