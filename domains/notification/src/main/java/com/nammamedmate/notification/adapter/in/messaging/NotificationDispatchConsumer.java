package com.nammamedmate.notification.adapter.in.messaging;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Catch-all for unknown outbox types whose payload already looks like a channel notification
 * ({@code channel}/{@code title}/{@code body}/{@code phone}/{@code email}).
 */
@Component
public class NotificationDispatchConsumer {

  private final CustomerNotificationRequestedHandler handler;
  private final ObjectMapper objectMapper;

  public NotificationDispatchConsumer(
      CustomerNotificationRequestedHandler handler, ObjectMapper objectMapper) {
    this.handler = handler;
    this.objectMapper = objectMapper;
  }

  public void handleMessage(String messageBody) {
    handler.handleMessage(messageBody);
  }

  /**
   * @return true if the payload was treated as a notification (handler may still throw)
   */
  public boolean tryHandle(String messageBody) {
    if (messageBody == null || messageBody.isBlank()) {
      return false;
    }
    Map<String, Object> root;
    try {
      root = objectMapper.readValue(messageBody, new TypeReference<Map<String, Object>>() {});
    } catch (Exception e) {
      throw new IllegalStateException("Failed to parse domain event", e);
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> payload =
        root.get("payload") instanceof Map<?, ?> m ? (Map<String, Object>) m : root;
    if (!looksDispatchable(payload)) {
      return false;
    }
    handler.handleMessage(messageBody);
    return true;
  }

  static boolean looksDispatchable(Map<String, Object> payload) {
    if (payload == null || payload.isEmpty()) {
      return false;
    }
    return has(payload, "channel")
        || has(payload, "channels")
        || has(payload, "title")
        || has(payload, "body")
        || has(payload, "phone")
        || has(payload, "to_phone")
        || has(payload, "email")
        || has(payload, "to_email");
  }

  private static boolean has(Map<String, Object> payload, String key) {
    Object raw = payload.get(key);
    if (raw == null) {
      return false;
    }
    return !String.valueOf(raw).trim().isEmpty();
  }
}
