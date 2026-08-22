package com.nammamedmate.notification.adapter.in.messaging;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.notification.application.InAppNotificationService;
import com.nammamedmate.notification.domain.InAppNotification;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code customer.notification.requested} outbox/SQS payloads and creates in-app
 * ORDER_UPDATE rows when title/body are present (typically PUSH channel events).
 */
@Component
public class CustomerNotificationRequestedHandler {

  private static final Logger log =
      LoggerFactory.getLogger(CustomerNotificationRequestedHandler.class);
  private static final String EVENT_TYPE = "customer.notification.requested";

  private final InAppNotificationService notifications;
  private final ObjectMapper objectMapper;

  public CustomerNotificationRequestedHandler(
      InAppNotificationService notifications, ObjectMapper objectMapper) {
    this.notifications = notifications;
    this.objectMapper = objectMapper;
  }

  /** Worker entry: full DomainEvent JSON or a bare payload map. */
  public void handleMessage(String messageBody) {
    if (messageBody == null || messageBody.isBlank()) {
      return;
    }
    try {
      Map<String, Object> root =
          objectMapper.readValue(messageBody, new TypeReference<Map<String, Object>>() {});
      String type = stringVal(root.get("type"));
      @SuppressWarnings("unchecked")
      Map<String, Object> payload =
          root.get("payload") instanceof Map<?, ?> m ? (Map<String, Object>) m : root;
      if (type != null && !EVENT_TYPE.equals(type)) {
        return;
      }
      handlePayload(payload);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Failed to handle notification event", e);
    }
  }

  public InAppNotification handlePayload(Map<String, Object> payload) {
    if (payload == null || payload.isEmpty()) {
      return null;
    }
    String title = stringVal(payload.get("title"));
    String body = stringVal(payload.get("body"));
    if (title == null || body == null) {
      return null;
    }
    UUID customerId = parseUuid(payload.get("customer_id"));
    if (customerId == null) {
      return null;
    }
    String channel = stringVal(payload.get("channel"));
    if (channel != null && !"PUSH".equalsIgnoreCase(channel)) {
      return null;
    }
    String orderId = stringVal(payload.get("order_id"));
    String actionUrl =
        orderId == null ? stringVal(payload.get("action_url")) : "nmmedmate://order/" + orderId;
    return notifications.createOrderUpdate(customerId, title, body, actionUrl);
  }

  private static UUID parseUuid(Object raw) {
    if (raw == null) {
      return null;
    }
    try {
      return UUID.fromString(String.valueOf(raw));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static String stringVal(Object raw) {
    if (raw == null) {
      return null;
    }
    String s = String.valueOf(raw).trim();
    return s.isEmpty() ? null : s;
  }
}
