package com.nammamedmate.order.adapter.out.persistence;

import com.nammamedmate.order.application.port.out.OrderNoteStore;
import com.nammamedmate.order.domain.OrderNote;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public class JdbcOrderNoteStore implements OrderNoteStore {

  private final JdbcTemplate jdbc;

  public JdbcOrderNoteStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public OrderNote insert(OrderNote note) {
    jdbc.update(
        """
        INSERT INTO order_note (id, order_id, note, is_pinned, added_by, created_at)
        VALUES (?, ?, ?, ?, ?, ?)
        """,
        note.id(),
        note.orderId(),
        note.note(),
        note.pinned(),
        note.addedBy(),
        Timestamp.from(note.createdAt()));
    return note;
  }

  @Override
  public List<OrderNote> listByOrderId(UUID orderId) {
    return jdbc.query(
        """
        SELECT * FROM order_note
        WHERE order_id = ?
        ORDER BY is_pinned DESC, created_at DESC
        """,
        this::map,
        orderId);
  }

  private OrderNote map(ResultSet rs, int i) throws SQLException {
    return new OrderNote(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("order_id"),
        rs.getString("note"),
        rs.getBoolean("is_pinned"),
        (UUID) rs.getObject("added_by"),
        rs.getTimestamp("created_at").toInstant());
  }
}
