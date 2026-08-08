package com.nammamedmate.rider.adapter.out.persistence;

import com.nammamedmate.rider.application.port.out.ActiveDeliveryPort;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JDBC orders lookup (composition-root bean). No Gradle dep on domains/order — raw SQL only.
 *
 * <p>Story ON_TRIP maps to orders.status = OUT_FOR_DELIVERY (and assigned non-terminal as active).
 */
public class JdbcActiveDeliveryAdapter implements ActiveDeliveryPort {

  private static final String ACTIVE_STATUSES = "'READY_FOR_PICKUP','OUT_FOR_DELIVERY'";

  private final JdbcTemplate jdbc;

  public JdbcActiveDeliveryAdapter(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<ActiveOrder> findActiveByRider(UUID riderId) {
    String sql =
        """
        SELECT o.id, o.status, a.area_locality,
               CASE
                 WHEN o.estimated_delivery_at IS NULL THEN NULL
                 ELSE GREATEST(
                   0,
                   CAST(EXTRACT(EPOCH FROM (o.estimated_delivery_at - NOW())) / 60 AS INTEGER)
                 )
               END AS eta_minutes
        FROM orders o
        JOIN customer_addresses a ON a.id = o.delivery_address_id
        WHERE o.rider_id = ?
          AND o.deleted_at IS NULL
          AND o.status IN (
        """
            + ACTIVE_STATUSES
            + """
              )
        ORDER BY o.updated_at DESC
        LIMIT 1
        """;
    List<ActiveOrder> rows =
        jdbc.query(
            sql,
            (rs, i) ->
                new ActiveOrder(
                    (UUID) rs.getObject("id"),
                    rs.getString("status"),
                    rs.getString("area_locality"),
                    (Integer) rs.getObject("eta_minutes")),
            riderId);
    return rows.stream().findFirst();
  }

  @Override
  public int countLiveOrdersInZone(UUID zoneId) {
    Integer n =
        jdbc.queryForObject(
            """
            SELECT COUNT(1)
            FROM orders o
            JOIN pharmacies p ON p.id = o.pharmacy_id
            WHERE o.deleted_at IS NULL
              AND p.zone_id = ?
              AND o.status IN (
                'PENDING_ACCEPTANCE','ACCEPTED','PACKING','READY_FOR_PICKUP','OUT_FOR_DELIVERY'
              )
            """,
            Integer.class,
            zoneId);
    return n == null ? 0 : n;
  }

  @Override
  public void flagForMonitoring(UUID orderId, String reason) {
    // ponytail: append monitoring flag into delivery_instructions prefix until dedicated column
    jdbc.update(
        """
        UPDATE orders
        SET delivery_instructions = LEFT(
              CONCAT('[', ?, '] ', COALESCE(delivery_instructions, '')),
              200
            ),
            updated_at = NOW()
        WHERE id = ? AND deleted_at IS NULL
        """,
        reason,
        orderId);
  }
}
