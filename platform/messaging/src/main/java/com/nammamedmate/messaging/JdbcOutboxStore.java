package com.nammamedmate.messaging;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Persists domain events to {@code outbox_message} and claims them with {@code SKIP LOCKED}. */
public final class JdbcOutboxStore implements OutboxStore {

  static final int MAX_ATTEMPTS = 8;

  private static final RowMapper<OutboxMessage> MAPPER =
      (rs, rowNum) ->
          new OutboxMessage(
              rs.getObject("id", UUID.class),
              rs.getString("type"),
              rs.getString("payload_json"),
              rs.getTimestamp("created_at").toInstant(),
              rs.getBoolean("published"));

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
  public List<OutboxMessage> claimUnpublished(int limit, String lockedBy, Duration lease) {
    Duration hold =
        lease == null || lease.isZero() || lease.isNegative() ? Duration.ofMinutes(1) : lease;
    String owner = lockedBy == null || lockedBy.isBlank() ? "dispatcher" : lockedBy.trim();
    Instant now = Instant.now();
    Timestamp leaseUntil = Timestamp.from(now.plus(hold));
    return jdbc.query(
        """
        UPDATE outbox_message
           SET locked_at = ?, locked_by = ?, attempts = attempts + 1
         WHERE id IN (
           SELECT id FROM outbox_message
            WHERE published = FALSE
              AND poisoned = FALSE
              AND attempts < ?
              AND (locked_at IS NULL OR locked_at < ?)
            ORDER BY created_at ASC
            LIMIT ?
            FOR UPDATE SKIP LOCKED
         )
        RETURNING id, type, payload_json, created_at, published
        """,
        MAPPER,
        leaseUntil,
        owner,
        MAX_ATTEMPTS,
        Timestamp.from(now),
        limit);
  }

  @Override
  public void markPublished(OutboxMessage message) {
    jdbc.update(
        "UPDATE outbox_message SET published = TRUE, published_at = ?, locked_at = NULL, last_error = NULL WHERE id = ?",
        Timestamp.from(Instant.now()),
        message.id());
  }

  @Override
  public void markFailed(OutboxMessage message, String error) {
    String text = error == null ? "transport failed" : error;
    int poisoned =
        jdbc.update(
            """
            UPDATE outbox_message
               SET last_error = ?, locked_at = NULL, poisoned = TRUE
             WHERE id = ? AND attempts >= ?
            """,
            text,
            message.id(),
            MAX_ATTEMPTS);
    if (poisoned == 0) {
      jdbc.update(
          "UPDATE outbox_message SET last_error = ?, locked_at = NULL WHERE id = ?",
          text,
          message.id());
    }
  }

  @Override
  public void markPoisoned(OutboxMessage message, String error) {
    jdbc.update(
        """
        UPDATE outbox_message
           SET last_error = ?, locked_at = NULL, poisoned = TRUE
         WHERE id = ?
        """,
        error == null ? "poisoned" : error,
        message.id());
  }
}
