package com.nammamedmate.notification.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.notification.application.port.out.BroadcastStore;
import com.nammamedmate.notification.domain.BroadcastAudience;
import com.nammamedmate.notification.domain.BroadcastStatus;
import com.nammamedmate.notification.domain.PushBroadcast;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcBroadcastStore implements BroadcastStore {

  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private static final String SELECT =
      """
      SELECT id, audience, title, body, data, schedule_at, status, estimated_recipients,
             created_by, created_at, executed_at
      FROM push_broadcasts
      """;

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public JdbcBroadcastStore(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  @Override
  public PushBroadcast insert(PushBroadcast broadcast) {
    jdbc.update(
        """
        INSERT INTO push_broadcasts (
          id, audience, title, body, data, schedule_at, status, estimated_recipients,
          created_by, created_at, executed_at
        ) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)
        """,
        broadcast.id(),
        broadcast.audience().name(),
        broadcast.title(),
        broadcast.body(),
        writeJson(broadcast.data()),
        ts(broadcast.scheduleAt()),
        broadcast.status().name(),
        broadcast.estimatedRecipients(),
        broadcast.createdBy(),
        Timestamp.from(broadcast.createdAt()),
        ts(broadcast.executedAt()));
    return broadcast;
  }

  @Override
  public Optional<PushBroadcast> findById(UUID id) {
    List<PushBroadcast> rows = jdbc.query(SELECT + " WHERE id = ?", (rs, i) -> map(rs), id);
    return rows.stream().findFirst();
  }

  @Override
  public List<PushBroadcast> findDueQueued(Instant now, int limit) {
    return jdbc.query(
        SELECT
            + """
              WHERE status = 'QUEUED'
                AND (schedule_at IS NULL OR schedule_at <= ?)
              ORDER BY created_at ASC
              LIMIT ?
              """,
        (rs, i) -> map(rs),
        Timestamp.from(now),
        limit);
  }

  @Override
  public boolean claimRunning(UUID id, Instant now) {
    int n =
        jdbc.update(
            """
            UPDATE push_broadcasts
            SET status = 'RUNNING'
            WHERE id = ? AND status = 'QUEUED'
            """,
            id);
    return n > 0;
  }

  @Override
  public void updateStatus(
      UUID id, BroadcastStatus status, Instant executedAt, Integer estimatedRecipients) {
    if (estimatedRecipients != null && executedAt != null) {
      jdbc.update(
          """
          UPDATE push_broadcasts
          SET status = ?, executed_at = ?, estimated_recipients = ?
          WHERE id = ?
          """,
          status.name(),
          Timestamp.from(executedAt),
          estimatedRecipients,
          id);
    } else if (estimatedRecipients != null) {
      jdbc.update(
          """
          UPDATE push_broadcasts
          SET status = ?, estimated_recipients = ?
          WHERE id = ?
          """,
          status.name(),
          estimatedRecipients,
          id);
    } else if (executedAt != null) {
      jdbc.update(
          """
          UPDATE push_broadcasts
          SET status = ?, executed_at = ?
          WHERE id = ?
          """,
          status.name(),
          Timestamp.from(executedAt),
          id);
    } else {
      jdbc.update("UPDATE push_broadcasts SET status = ? WHERE id = ?", status.name(), id);
    }
  }

  private PushBroadcast map(ResultSet rs) throws SQLException {
    Timestamp schedule = rs.getTimestamp("schedule_at");
    Timestamp executed = rs.getTimestamp("executed_at");
    return new PushBroadcast(
        (UUID) rs.getObject("id"),
        BroadcastAudience.valueOf(rs.getString("audience")),
        rs.getString("title"),
        rs.getString("body"),
        readJson(rs.getString("data")),
        schedule == null ? null : schedule.toInstant(),
        BroadcastStatus.valueOf(rs.getString("status")),
        rs.getInt("estimated_recipients"),
        (UUID) rs.getObject("created_by"),
        rs.getTimestamp("created_at").toInstant(),
        executed == null ? null : executed.toInstant());
  }

  private String writeJson(Map<String, Object> data) {
    try {
      return mapper.writeValueAsString(data);
    } catch (JsonProcessingException e) {
      return "{}";
    }
  }

  private Map<String, Object> readJson(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    try {
      return mapper.readValue(json, MAP_TYPE);
    } catch (JsonProcessingException e) {
      return Map.of();
    }
  }

  private static Timestamp ts(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }
}
