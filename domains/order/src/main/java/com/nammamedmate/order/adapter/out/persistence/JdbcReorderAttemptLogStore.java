package com.nammamedmate.order.adapter.out.persistence;

import com.nammamedmate.order.application.port.out.ReorderAttemptLogStore;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;

public class JdbcReorderAttemptLogStore implements ReorderAttemptLogStore {

  private final JdbcTemplate jdbc;

  public JdbcReorderAttemptLogStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(ReorderAttemptLog row) {
    jdbc.update(
        """
        INSERT INTO reorder_attempt_log (
          id, customer_id, source_order_id, resulting_cart_id, pharmacy_changed,
          items_requested, items_added, items_excluded, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        row.id(),
        row.customerId(),
        row.sourceOrderId(),
        row.resultingCartId(),
        row.pharmacyChanged(),
        row.itemsRequested(),
        row.itemsAdded(),
        row.itemsExcluded(),
        Timestamp.from(row.createdAt()));
  }
}
