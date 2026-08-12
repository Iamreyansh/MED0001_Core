package com.nammamedmate.automation.adapter.out.persistence;

import com.nammamedmate.automation.application.port.out.KillSwitchPort;
import com.nammamedmate.automation.domain.KillSwitchAction;
import com.nammamedmate.automation.domain.KillSwitchChange;
import com.nammamedmate.automation.domain.KillSwitchStatus;
import com.nammamedmate.kernel.id.Ids;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcKillSwitchAdapter implements KillSwitchPort {

  /** ponytail: in-process TTL cache (Redis later) — well under the 60s halt SLO. */
  static final Duration CACHE_TTL = Duration.ofSeconds(5);

  private final JdbcTemplate jdbc;
  private final Clock clock;

  private volatile KillSwitchStatus cached;
  private volatile Instant cacheUntil = Instant.EPOCH;

  public JdbcKillSwitchAdapter(JdbcTemplate jdbc) {
    this(jdbc, Clock.systemUTC());
  }

  @Autowired
  public JdbcKillSwitchAdapter(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock == null ? Clock.systemUTC() : clock;
  }

  @Override
  public KillSwitchStatus status() {
    Instant now = clock.instant();
    KillSwitchStatus hit = cached;
    if (hit != null && now.isBefore(cacheUntil)) {
      return hit;
    }
    KillSwitchStatus loaded = load();
    cached = loaded;
    cacheUntil = now.plus(CACHE_TTL);
    return loaded;
  }

  @Override
  public void setStatus(KillSwitchStatus next, UUID actorId, String reason) {
    KillSwitchStatus target = next == null ? KillSwitchStatus.ACTIVE : next;
    Instant now = clock.instant();
    jdbc.update(
        """
        INSERT INTO automation_health_config (config_key, config_value, updated_by, updated_at)
        VALUES ('kill_switch_status', ?, ?, ?)
        ON CONFLICT (config_key) DO UPDATE SET
          config_value = EXCLUDED.config_value,
          updated_by = EXCLUDED.updated_by,
          updated_at = EXCLUDED.updated_at
        """,
        target.name(),
        actorId,
        Timestamp.from(now));
    KillSwitchAction action =
        target == KillSwitchStatus.PAUSED ? KillSwitchAction.PAUSE : KillSwitchAction.RESUME;
    jdbc.update(
        """
        INSERT INTO automation_kill_switch_log (id, action, changed_by, reason, changed_at)
        VALUES (?, ?, ?, ?, ?)
        """,
        Ids.newId(),
        action.name(),
        actorId,
        reason,
        Timestamp.from(now));
    cached = target;
    cacheUntil = now.plus(CACHE_TTL);
  }

  @Override
  public Optional<KillSwitchChange> lastChange() {
    List<KillSwitchChange> rows =
        jdbc.query(
            """
            SELECT l.action, l.changed_by, l.reason, l.changed_at, s.email
            FROM automation_kill_switch_log l
            LEFT JOIN admin_staff s ON s.id = l.changed_by AND s.deleted_at IS NULL
            ORDER BY l.changed_at DESC
            LIMIT 1
            """,
            (rs, i) -> {
              Timestamp at = rs.getTimestamp("changed_at");
              UUID actor = (UUID) rs.getObject("changed_by");
              String email = rs.getString("email");
              String label = email == null || email.isBlank() ? String.valueOf(actor) : email;
              return new KillSwitchChange(
                  KillSwitchAction.parse(rs.getString("action")),
                  actor,
                  label,
                  at == null ? Instant.EPOCH : at.toInstant(),
                  rs.getString("reason"));
            });
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  private KillSwitchStatus load() {
    List<String> rows =
        jdbc.query(
            """
            SELECT config_value FROM automation_health_config
            WHERE config_key = 'kill_switch_status'
            """,
            (rs, i) -> rs.getString(1));
    if (rows.isEmpty()) {
      return KillSwitchStatus.ACTIVE;
    }
    return KillSwitchStatus.from(rows.getFirst());
  }
}
