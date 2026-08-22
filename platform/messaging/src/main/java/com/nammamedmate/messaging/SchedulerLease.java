package com.nammamedmate.messaging;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Multi-instance lock for {@code @Scheduled} jobs via {@code scheduler_lease}. */
public final class SchedulerLease {

  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final String instanceId;

  public SchedulerLease(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.clock = clock == null ? Clock.systemUTC() : clock;
    this.instanceId = UUID.randomUUID().toString();
  }

  public boolean tryAcquire(String jobName, Duration ttl) {
    if (jobName == null || jobName.isBlank()) {
      return false;
    }
    Duration hold = ttl == null || ttl.isNegative() || ttl.isZero() ? Duration.ofMinutes(5) : ttl;
    Instant until = clock.instant().plus(hold);
    String name = jobName.trim();
    int updated =
        jdbc.update(
            """
            UPDATE scheduler_lease
               SET locked_by = ?, locked_until = ?
             WHERE job_name = ?
               AND locked_until < ?
            """,
            instanceId,
            java.sql.Timestamp.from(until),
            name,
            java.sql.Timestamp.from(clock.instant()));
    if (updated > 0) {
      return true;
    }
    try {
      jdbc.update(
          """
          INSERT INTO scheduler_lease (job_name, locked_by, locked_until)
          VALUES (?, ?, ?)
          """,
          name,
          instanceId,
          java.sql.Timestamp.from(until));
      return true;
    } catch (RuntimeException ex) {
      return false;
    }
  }
}
