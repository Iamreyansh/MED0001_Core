package com.nammamedmate.messaging;

import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcWebhookInbox implements WebhookInbox {

  private final JdbcTemplate jdbc;

  public JdbcWebhookInbox(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc);
  }

  @Override
  public boolean alreadyReceived(String provider, String providerEventId) {
    if (provider == null
        || provider.isBlank()
        || providerEventId == null
        || providerEventId.isBlank()) {
      return false;
    }
    Integer count =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM webhook_inbox
             WHERE provider = ? AND provider_event_id = ?
            """,
            Integer.class,
            provider.trim(),
            providerEventId.trim());
    return count != null && count > 0;
  }

  @Override
  public boolean claim(String provider, String providerEventId, String payloadJson) {
    if (provider == null
        || provider.isBlank()
        || providerEventId == null
        || providerEventId.isBlank()) {
      return false;
    }
    try {
      jdbc.update(
          """
          INSERT INTO webhook_inbox (id, provider, provider_event_id, payload_json, status)
          VALUES (?, ?, ?, ?, 'RECEIVED')
          """,
          UUID.randomUUID(),
          provider.trim(),
          providerEventId.trim(),
          payloadJson == null ? "" : payloadJson);
      return true;
    } catch (DuplicateKeyException ex) {
      return false;
    }
  }
}
