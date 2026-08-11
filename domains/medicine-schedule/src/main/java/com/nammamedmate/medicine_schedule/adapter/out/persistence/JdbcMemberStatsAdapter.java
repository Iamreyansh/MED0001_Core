package com.nammamedmate.medicine_schedule.adapter.out.persistence;

import com.nammamedmate.medicine_schedule.application.ReminderRecalcService;
import com.nammamedmate.medicine_schedule.application.port.out.MemberStatsPort;
import com.nammamedmate.medicine_schedule.domain.AdherenceMath;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcMemberStatsAdapter implements MemberStatsPort {

  private final JdbcTemplate jdbc;
  private final Clock clock;

  public JdbcMemberStatsAdapter(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @Override
  public MemberListStats statsForMember(UUID memberId) {
    Integer medicines =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM schedule_medicine
            WHERE member_id = ? AND is_active = TRUE
            """,
            Integer.class,
            memberId);
    Integer refillAlerts =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM schedule_medicine
            WHERE member_id = ?
              AND is_active = TRUE
              AND refill_remind_at_units > 0
              AND units_in_hand <= refill_remind_at_units
            """,
            Integer.class,
            memberId);

    LocalDate today = LocalDate.ofInstant(clock.instant(), ReminderRecalcService.IST);
    int[] todayCounts =
        jdbc.query(
            """
            SELECT
              COUNT(*)::int AS total,
              COUNT(*) FILTER (WHERE status = 'TAKEN')::int AS taken
            FROM dose_log
            WHERE member_id = ? AND dose_date = ?
            """,
            rs -> {
              if (!rs.next()) {
                return new int[] {0, 0};
              }
              return new int[] {rs.getInt("total"), rs.getInt("taken")};
            },
            memberId,
            java.sql.Date.valueOf(today));

    int total = todayCounts == null ? 0 : todayCounts[0];
    int taken = todayCounts == null ? 0 : todayCounts[1];
    // ponytail: story wrote -100; treat as ×100
    Double adherencePct = AdherenceMath.pct(taken, total);

    return new MemberListStats(
        medicines == null ? 0 : medicines,
        total,
        taken,
        adherencePct,
        refillAlerts == null ? 0 : refillAlerts);
  }
}
