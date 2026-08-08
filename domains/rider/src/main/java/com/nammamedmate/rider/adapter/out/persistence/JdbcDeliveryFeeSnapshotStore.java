package com.nammamedmate.rider.adapter.out.persistence;

import com.nammamedmate.rider.application.port.out.DeliveryFeeSnapshotStore;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDeliveryFeeSnapshotStore implements DeliveryFeeSnapshotStore {

  private final JdbcTemplate jdbc;

  public JdbcDeliveryFeeSnapshotStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(Snapshot snapshot) {
    jdbc.update(
        """
        INSERT INTO delivery_fee_snapshots (
          order_id, zone_id, distance_km, base_fee, distance_charge, surge_multiplier,
          delivery_fee, handling_fee, is_free_delivery, rider_payout, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (order_id) DO NOTHING
        """,
        snapshot.orderId(),
        snapshot.zoneId(),
        snapshot.distanceKm(),
        snapshot.baseFee(),
        snapshot.distanceCharge(),
        snapshot.surgeMultiplier(),
        snapshot.deliveryFee(),
        snapshot.handlingFee(),
        snapshot.freeDelivery(),
        snapshot.riderPayout(),
        Timestamp.from(snapshot.createdAt()));
  }

  @Override
  public Optional<Snapshot> findByOrderId(UUID orderId) {
    List<Snapshot> rows =
        jdbc.query(
            """
            SELECT order_id, zone_id, distance_km, base_fee, distance_charge, surge_multiplier,
                   delivery_fee, handling_fee, is_free_delivery, rider_payout, created_at
            FROM delivery_fee_snapshots
            WHERE order_id = ?
            """,
            (rs, i) ->
                new Snapshot(
                    (UUID) rs.getObject("order_id"),
                    (UUID) rs.getObject("zone_id"),
                    rs.getBigDecimal("distance_km"),
                    rs.getBigDecimal("base_fee"),
                    rs.getBigDecimal("distance_charge"),
                    rs.getBigDecimal("surge_multiplier"),
                    rs.getBigDecimal("delivery_fee"),
                    rs.getBigDecimal("handling_fee"),
                    rs.getBoolean("is_free_delivery"),
                    rs.getBigDecimal("rider_payout"),
                    rs.getTimestamp("created_at").toInstant()),
            orderId);
    return rows.stream().findFirst();
  }
}
