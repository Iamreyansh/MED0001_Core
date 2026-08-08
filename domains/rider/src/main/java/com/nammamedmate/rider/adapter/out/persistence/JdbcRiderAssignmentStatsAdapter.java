package com.nammamedmate.rider.adapter.out.persistence;

import com.nammamedmate.rider.application.port.out.RiderAssignmentStatsPort;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRiderAssignmentStatsAdapter implements RiderAssignmentStatsPort {

  private final JdbcTemplate jdbc;

  public JdbcRiderAssignmentStatsAdapter(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Stats statsForRider(UUID riderId) {
    return jdbc.query(
        """
        SELECT
          COUNT(1) AS assigned,
          COUNT(1) FILTER (WHERE accepted_at IS NOT NULL) AS accepted,
          COUNT(1) FILTER (
            WHERE status IN ('TIMED_OUT','CANCELLED','REASSIGNED')
          ) AS cancelled,
          COUNT(1) FILTER (WHERE status = 'DELIVERED') AS delivered,
          AVG(
            EXTRACT(EPOCH FROM (pickup_confirmed_at - accepted_at)) / 60.0
          ) FILTER (WHERE pickup_confirmed_at IS NOT NULL AND accepted_at IS NOT NULL)
            AS avg_pickup,
          AVG(
            EXTRACT(EPOCH FROM (delivered_at - accepted_at)) / 60.0
          ) FILTER (WHERE delivered_at IS NOT NULL AND accepted_at IS NOT NULL)
            AS avg_delivery
        FROM order_assignments
        WHERE rider_id = ?
        """,
        rs -> {
          rs.next();
          Double pickup = (Double) rs.getObject("avg_pickup");
          Double delivery = (Double) rs.getObject("avg_delivery");
          return new Stats(
              rs.getLong("assigned"),
              rs.getLong("accepted"),
              rs.getLong("cancelled"),
              rs.getLong("delivered"),
              pickup == null ? null : round1(pickup),
              delivery == null ? null : round1(delivery));
        },
        riderId);
  }

  private static double round1(double v) {
    return Math.round(v * 10.0) / 10.0;
  }
}
