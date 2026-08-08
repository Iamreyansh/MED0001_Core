package com.nammamedmate.api.config;

import com.nammamedmate.catalogue.application.port.out.OrderDemandPort;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Trailing-30-day order demand for a medicine (product_id in orders.items JSONB).
 *
 * <p>ponytail: {@code items::text LIKE} scan; upgrade to jsonb_path_exists when volume warrants.
 */
final class JdbcOrderDemandBridge implements OrderDemandPort {

  private final JdbcTemplate jdbc;

  JdbcOrderDemandBridge(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public int trailing30DayOrderCount(UUID medicineId) {
    if (medicineId == null) {
      return 0;
    }
    Instant since = Instant.now().minusSeconds(30L * 24 * 3600);
    Integer count =
        jdbc.queryForObject(
            """
            SELECT COUNT(*)::int FROM orders
            WHERE deleted_at IS NULL
              AND status <> 'CANCELLED'
              AND created_at >= ?
              AND items::text LIKE ?
            """,
            Integer.class,
            Timestamp.from(since),
            "%" + medicineId + "%");
    return count == null ? 0 : count;
  }
}
