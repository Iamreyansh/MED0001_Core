package com.nammamedmate.messaging;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Persists domain events to {@code outbox_message} for co-commit with JPA work. */
public final class JdbcOutboxStore implements OutboxStore {

  private final JdbcTemplate jdbc;

  public JdbcOutboxStore(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc);
  }

  @Override
  public void append(OutboxMessage message) {
    jdbc.update(
        "INSERT INTO outbox_message (id, type, payload_json, created_at, published) VALUES (?, ?, ?,"
            + " ?, ?)",
        message.id(),
        message.type(),
        message.payloadJson(),
        Timestamp.from(message.createdAt()),
        message.published());
  }

  @Override
  public List<OutboxMessage> findUnpublished(int limit) {
    return jdbc.query(
        "SELECT id, type, payload_json, created_at, published FROM outbox_message WHERE published ="
            + " FALSE ORDER BY created_at ASC LIMIT ?",
        (rs, rowNum) ->
            new OutboxMessage(
                rs.getObject("id", UUID.class),
                rs.getString("type"),
                rs.getString("payload_json"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getBoolean("published")),
        limit);
  }

  @Override
  public void markPublished(OutboxMessage message) {
    jdbc.update("UPDATE outbox_message SET published = TRUE WHERE id = ?", message.id());
  }
}
