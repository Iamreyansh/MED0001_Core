package com.nammamedmate.automation.adapter.out.persistence;

import com.nammamedmate.automation.application.port.out.CircuitBreakerPort;
import com.nammamedmate.automation.domain.CircuitBreakerState;
import com.nammamedmate.automation.domain.CircuitStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCircuitBreakerAdapter implements CircuitBreakerPort {

  private final JdbcTemplate jdbc;
  private final Clock clock;

  public JdbcCircuitBreakerAdapter(JdbcTemplate jdbc) {
    this(jdbc, Clock.systemUTC());
  }

  @Autowired
  public JdbcCircuitBreakerAdapter(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock == null ? Clock.systemUTC() : clock;
  }

  @Override
  public boolean tryAcquire(String actionType) {
    if (actionType == null || actionType.isBlank()) {
      return true;
    }
    Instant now = clock.instant();
    ensureRow(actionType);
    CircuitBreakerState current = load(actionType);
    CircuitBreakerState reset = current.maybeAutoReset(now);
    if (reset.status() != current.status()) {
      persist(reset, now);
      current = reset;
    }
    int fires = countFires(actionType, now.minus(Duration.ofHours(1)));
    if (current.status() == CircuitStatus.OPEN) {
      persist(current.withFires(fires, now), now);
      return false;
    }
    if (current.shouldOpen(fires)) {
      persist(current.open(now, fires, resetMinutes()), now);
      return false;
    }
    persist(current.withFires(fires, now), now);
    return true;
  }

  @Override
  public List<CircuitBreakerState> list() {
    Instant now = clock.instant();
    Instant hourAgo = now.minus(Duration.ofHours(1));
    List<CircuitBreakerState> rows =
        jdbc.query(
            """
            SELECT cb.action_type, cb.threshold_per_hour, cb.circuit_status, cb.fires_last_hour,
                   cb.opened_at, cb.reset_at, cb.updated_at,
                   (SELECT COUNT(*) FROM automation_activity_log a
                    WHERE a.action_type = cb.action_type
                      AND a.triggered_at >= ?
                      AND a.status IN ('EXECUTED', 'EXCEPTION')) AS live_fires
            FROM automation_circuit_breakers cb
            ORDER BY cb.action_type
            """,
            (rs, i) -> mapRow(rs, rs.getInt("live_fires")),
            Timestamp.from(hourAgo));
    List<CircuitBreakerState> out = new ArrayList<>();
    for (CircuitBreakerState row : rows) {
      CircuitBreakerState reset = row.maybeAutoReset(now);
      if (reset.status() != row.status()) {
        persist(reset.withFires(reset.firesLastHour(), now), now);
        out.add(reset);
      } else {
        out.add(row);
      }
    }
    return out;
  }

  private CircuitBreakerState load(String actionType) {
    List<CircuitBreakerState> rows =
        jdbc.query(
            """
            SELECT action_type, threshold_per_hour, circuit_status, fires_last_hour,
                   opened_at, reset_at, updated_at
            FROM automation_circuit_breakers
            WHERE action_type = ?
            """,
            (rs, i) -> mapRow(rs, rs.getInt("fires_last_hour")),
            actionType);
    if (rows.isEmpty()) {
      return new CircuitBreakerState(
          actionType,
          CircuitBreakerState.DEFAULT_THRESHOLD,
          CircuitStatus.CLOSED,
          0,
          null,
          null,
          clock.instant());
    }
    return rows.getFirst();
  }

  private void ensureRow(String actionType) {
    jdbc.update(
        """
        INSERT INTO automation_circuit_breakers (action_type, updated_at)
        VALUES (?, ?)
        ON CONFLICT (action_type) DO NOTHING
        """,
        actionType,
        Timestamp.from(clock.instant()));
  }

  private int countFires(String actionType, Instant since) {
    Integer n =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM automation_activity_log
            WHERE action_type = ? AND triggered_at >= ?
              AND status IN ('EXECUTED', 'EXCEPTION')
            """,
            Integer.class,
            actionType,
            Timestamp.from(since));
    return n == null ? 0 : n;
  }

  private int resetMinutes() {
    List<String> rows;
    try {
      rows =
          jdbc.query(
              """
              SELECT config_value FROM automation_health_config
              WHERE config_key = ?
              """,
              new Object[] {"circuit_reset_minutes"},
              (rs, i) -> rs.getString(1));
    } catch (RuntimeException ex) {
      return CircuitBreakerState.DEFAULT_RESET_MINUTES;
    }
    return CircuitBreakerState.parseResetMinutes(
        rows == null || rows.isEmpty() ? null : rows.getFirst());
  }

  private void persist(CircuitBreakerState state, Instant now) {
    jdbc.update(
        """
        UPDATE automation_circuit_breakers SET
          threshold_per_hour = ?, circuit_status = ?, fires_last_hour = ?,
          opened_at = ?, reset_at = ?, updated_at = ?
        WHERE action_type = ?
        """,
        state.thresholdPerHour(),
        state.status().name(),
        state.firesLastHour(),
        state.openedAt() == null ? null : Timestamp.from(state.openedAt()),
        state.resetAt() == null ? null : Timestamp.from(state.resetAt()),
        Timestamp.from(now),
        state.actionType());
  }

  private static CircuitBreakerState mapRow(ResultSet rs, int fires) throws SQLException {
    Timestamp opened = rs.getTimestamp("opened_at");
    Timestamp reset = rs.getTimestamp("reset_at");
    Timestamp updated = rs.getTimestamp("updated_at");
    return new CircuitBreakerState(
        rs.getString("action_type"),
        rs.getInt("threshold_per_hour"),
        CircuitStatus.from(rs.getString("circuit_status")),
        fires,
        opened == null ? null : opened.toInstant(),
        reset == null ? null : reset.toInstant(),
        updated == null ? Instant.EPOCH : updated.toInstant());
  }
}
