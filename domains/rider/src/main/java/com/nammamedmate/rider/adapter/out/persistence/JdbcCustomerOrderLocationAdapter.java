package com.nammamedmate.rider.adapter.out.persistence;

import com.nammamedmate.rider.application.port.out.CustomerOrderLocationPort;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** JDBC orders/riders/addresses — composition-root bean; no domains/order Gradle dep. */
public class JdbcCustomerOrderLocationAdapter implements CustomerOrderLocationPort {

  private final JdbcTemplate jdbc;

  public JdbcCustomerOrderLocationAdapter(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<OrderLocationContext> findById(UUID orderId) {
    List<OrderLocationContext> rows =
        jdbc.query(
            """
            SELECT o.id, o.customer_id, o.status, o.rider_id, r.name AS rider_name,
                   a.latitude AS delivery_lat, a.longitude AS delivery_lng
            FROM orders o
            LEFT JOIN riders r ON r.id = o.rider_id AND r.deleted_at IS NULL
            JOIN customer_addresses a ON a.id = o.delivery_address_id
            WHERE o.id = ? AND o.deleted_at IS NULL
            """,
            (rs, i) ->
                new OrderLocationContext(
                    (UUID) rs.getObject("id"),
                    (UUID) rs.getObject("customer_id"),
                    rs.getString("status"),
                    (UUID) rs.getObject("rider_id"),
                    rs.getString("rider_name"),
                    (Double) rs.getObject("delivery_lat"),
                    (Double) rs.getObject("delivery_lng")),
            orderId);
    return rows.stream().findFirst();
  }
}
