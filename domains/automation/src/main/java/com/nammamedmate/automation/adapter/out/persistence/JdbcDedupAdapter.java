package com.nammamedmate.automation.adapter.out.persistence;

import com.nammamedmate.automation.application.port.out.DedupPort;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Durable (rule, entity) fire window via automation_dedup. */
@Component
@Primary
public class JdbcDedupAdapter implements DedupPort {

  private final JdbcTemplate jdbc;
  private final Clock clock;

  public JdbcDedupAdapter(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @Override
  public boolean isDuplicate(UUID ruleId, UUID entityId, Duration window) {
    if (ruleId == null
        || entityId == null
        || window == null
        || window.isZero()
        || window.isNegative()) {
      return false;
    }
    Instant cutoff = clock.instant().minus(window);
    Long n =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM automation_dedup
             WHERE rule_id = ? AND entity_id = ? AND last_fired_at > ?
            """,
            Long.class,
            ruleId,
            entityId,
            Timestamp.from(cutoff));
    return n != null && n > 0;
  }

  @Override
  public void recordFire(UUID ruleId, UUID entityId) {
    if (ruleId == null || entityId == null) {
      return;
    }
    Timestamp now = Timestamp.from(clock.instant());
    jdbc.update(
        """
        INSERT INTO automation_dedup (rule_id, entity_id, last_fired_at)
        VALUES (?, ?, ?)
        ON CONFLICT (rule_id, entity_id) DO UPDATE SET last_fired_at = EXCLUDED.last_fired_at
        """,
        ruleId,
        entityId,
        now);
  }
}
