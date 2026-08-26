package com.nammamedmate.messaging;

import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcConsumerInbox implements ConsumerInbox {

  private final JdbcTemplate jdbc;

  public JdbcConsumerInbox(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc);
  }

  @Override
  public boolean alreadyProcessed(String consumerName, UUID eventId) {
    if (consumerName == null || consumerName.isBlank() || eventId == null) {
      return false;
    }
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM consumer_inbox WHERE consumer_name = ? AND event_id = ?",
            Integer.class,
            consumerName.trim(),
            eventId);
    return count != null && count > 0;
  }

  @Override
  public boolean claim(String consumerName, UUID eventId) {
    if (consumerName == null || consumerName.isBlank() || eventId == null) {
      return false;
    }
    try {
      jdbc.update(
          """
          INSERT INTO consumer_inbox (consumer_name, event_id)
          VALUES (?, ?)
          """,
          consumerName.trim(),
          eventId);
      return true;
    } catch (DuplicateKeyException ex) {
      return false;
    }
  }
}
