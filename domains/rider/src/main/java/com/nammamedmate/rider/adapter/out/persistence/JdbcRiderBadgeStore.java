package com.nammamedmate.rider.adapter.out.persistence;

import com.nammamedmate.rider.application.port.out.RiderBadgeStore;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRiderBadgeStore implements RiderBadgeStore {

  private final JdbcTemplate jdbc;

  public JdbcRiderBadgeStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<BadgeRow> listForRider(UUID riderId) {
    return jdbc.query(
        """
        SELECT badge, earned_at FROM rider_performance_badges
        WHERE rider_id = ? ORDER BY earned_at DESC
        """,
        (rs, i) -> new BadgeRow(rs.getString("badge"), rs.getDate("earned_at").toLocalDate()),
        riderId);
  }

  @Override
  public void upsert(UUID id, UUID riderId, String badge, LocalDate earnedAt) {
    jdbc.update(
        """
        INSERT INTO rider_performance_badges (id, rider_id, badge, earned_at, created_at)
        VALUES (?,?,?,?,NOW())
        ON CONFLICT (rider_id, badge) DO NOTHING
        """,
        id,
        riderId,
        badge,
        Date.valueOf(earnedAt));
  }
}
