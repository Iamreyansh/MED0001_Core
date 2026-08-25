package com.nammamedmate.notification.adapter.in.messaging;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.notification.application.EmailSendService;
import com.nammamedmate.notification.application.InAppNotificationService;
import com.nammamedmate.notification.application.PushSendService;
import com.nammamedmate.notification.application.SmsSendService;
import com.nammamedmate.notification.application.WhatsAppSendService;
import com.nammamedmate.notification.application.port.out.RecipientIdentityPort;
import com.nammamedmate.notification.domain.InAppNotification;
import com.nammamedmate.notification.domain.InAppNotificationType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Dispatches outbox notification payloads to in-app + EPIC-017 send services. Failures propagate
 * for SQS retry/DLQ.
 */
@Component
public class CustomerNotificationRequestedHandler {

  private static final Logger log =
      LoggerFactory.getLogger(CustomerNotificationRequestedHandler.class);

  private final InAppNotificationService notifications;
  private final ObjectMapper objectMapper;
  private final PushSendService push;
  private final SmsSendService sms;
  private final WhatsAppSendService whatsapp;
  private final EmailSendService email;
  private final RecipientIdentityPort identities;

  public CustomerNotificationRequestedHandler(
      InAppNotificationService notifications, ObjectMapper objectMapper) {
    this(notifications, objectMapper, null, null, null, null, null);
  }

  @Autowired
  public CustomerNotificationRequestedHandler(
      InAppNotificationService notifications,
      ObjectMapper objectMapper,
      PushSendService push,
      SmsSendService sms,
      WhatsAppSendService whatsapp,
      EmailSendService email,
      RecipientIdentityPort identities) {
    this.notifications = notifications;
    this.objectMapper = objectMapper;
    this.push = push;
    this.sms = sms;
    this.whatsapp = whatsapp;
    this.email = email;
    this.identities = identities;
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
      handlePayload(type, payload);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Failed to handle notification event", e);
    }
  }

  public InAppNotification handlePayload(Map<String, Object> payload) {
    return handlePayload(null, payload);
  }

  public InAppNotification handlePayload(String type, Map<String, Object> payload) {
    if (payload == null || payload.isEmpty()) {
      return null;
    }
    Map<String, Object> work = new LinkedHashMap<>(payload);
    applyTypeDefaults(type, work);
    List<String> channels = resolveChannels(work);
    if (channels.isEmpty()) {
      return null;
    }
    InAppNotification created = null;
    for (String channel : channels) {
      InAppNotification row = dispatchChannel(channel, work);
      if (row != null) {
        created = row;
      }
    }
    return created;
  }

  private InAppNotification dispatchChannel(String channel, Map<String, Object> payload) {
    return switch (channel) {
      case "PUSH" -> dispatchPush(payload, true);
      case "IN_APP" -> dispatchPush(payload, false);
      case "SMS" -> {
        dispatchSms(payload);
        yield null;
      }
      case "WHATSAPP" -> {
        dispatchWhatsApp(payload);
        yield null;
      }
      case "EMAIL" -> {
        dispatchEmail(payload);
        yield null;
      }
      default -> {
        log.warn("Unsupported notification channel={}", channel);
        yield null;
      }
    };
  }

  private InAppNotification dispatchPush(Map<String, Object> payload, boolean sendFcm) {
    String title = stringVal(payload.get("title"));
    String body = first(payload, "body", "message");
    UUID customerId = parseUuid(payload.get("customer_id"));
    String orderId = stringVal(payload.get("order_id"));
    String actionUrl =
        orderId == null
            ? first(payload, "action_url", "deep_link")
            : "nmmedmate://order/" + orderId;
    InAppNotification created = null;
    if (customerId != null && title != null && body != null) {
      created = notifications.create(customerId, inAppType(payload), title, body, actionUrl);
    }
    if (!sendFcm) {
      return created;
    }
    if (push == null) {
      return created;
    }
    List<UUID> ids = recipientIds(payload, customerId);
    if (ids.isEmpty()) {
      return created;
    }
    String recipientType = stringVal(payload.get("recipient_type"));
    if (recipientType == null) {
      recipientType = "CUSTOMER";
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> data =
        payload.get("data") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    push.send(
        new PushSendService.SendCommand(
            recipientType,
            ids,
            title,
            body,
            data,
            stringVal(payload.get("image_url")),
            actionUrl,
            stringVal(payload.get("priority")),
            parseUuid(payload.get("broadcast_id"))));
    return created;
  }

  private void dispatchSms(Map<String, Object> payload) {
    if (sms == null) {
      throw new IllegalStateException("No consumer for SMS");
    }
    String phone = resolvePhone(payload);
    String template = first(payload, "template", "template_id");
    if (phone == null || template == null) {
      log.warn("SMS skipped — missing phone or template");
      return;
    }
    sms.send(
        new SmsSendService.SendCommand(
            phone,
            template,
            stringVars(payload.get("variables")),
            stringVal(payload.get("priority"))));
  }

  private void dispatchWhatsApp(Map<String, Object> payload) {
    if (whatsapp == null) {
      throw new IllegalStateException("No consumer for WHATSAPP");
    }
    String phone = resolvePhone(payload);
    String template = first(payload, "template", "template_name");
    if (phone == null || template == null) {
      log.warn("WhatsApp skipped — missing phone or template");
      return;
    }
    String language = stringVal(payload.get("template_language"));
    whatsapp.send(
        new WhatsAppSendService.SendCommand(
            phone, template, language, components(payload.get("components"))));
  }

  private void dispatchEmail(Map<String, Object> payload) {
    if (email == null) {
      throw new IllegalStateException("No consumer for EMAIL");
    }
    String to = first(payload, "email", "to_email");
    String template = first(payload, "template", "template_id");
    if (to == null || template == null) {
      log.warn("Email skipped — missing email or template");
      return;
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> vars =
        payload.get("variables") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    email.send(
        new EmailSendService.SendCommand(
            to,
            stringVal(payload.get("to_name")),
            template,
            vars,
            List.of(),
            parseUuid(payload.get("customer_id"))));
  }

  private String resolvePhone(Map<String, Object> payload) {
    String phone = first(payload, "phone", "to_phone", "mobile");
    if (phone != null) {
      return phone;
    }
    UUID riderId = parseUuid(payload.get("rider_id"));
    if (identities != null && riderId != null) {
      Optional<String> riderPhone = identities.findPhoneByRiderId(riderId);
      if (riderPhone.isPresent()) {
        return riderPhone.get();
      }
    }
    UUID customerId = parseUuid(payload.get("customer_id"));
    if (identities == null || customerId == null) {
      return null;
    }
    return identities.findPhoneByCustomerId(customerId).orElse(null);
  }

  private static void applyTypeDefaults(String type, Map<String, Object> payload) {
    if (type == null) {
      return;
    }
    String medicine = first(payload, "medicine_name", "medicine");
    if (medicine == null) {
      medicine = "your medicine";
    }
    if ("medicine_schedule.notification.dose_reminder".equals(type)) {
      payload.putIfAbsent("channel", "PUSH");
      payload.putIfAbsent("title", "Dose reminder");
      payload.putIfAbsent("body", "Time to take " + medicine);
    } else if ("medicine_schedule.notification.refill_alert".equals(type)) {
      payload.putIfAbsent("channel", "PUSH");
      payload.putIfAbsent("title", "Refill alert");
      payload.putIfAbsent("body", "Time to refill " + medicine);
    } else if ("observability.alert.critical_page".equals(type)) {
      payload.putIfAbsent("channel", "PUSH");
      payload.putIfAbsent("title", "Critical alert");
      String alertBody = first(payload, "alert_type", "message");
      payload.putIfAbsent("body", alertBody == null ? "Critical page" : alertBody);
    } else if ("observability.incident.declared".equals(type)
        || "observability.incident.postmortem_reminder".equals(type)) {
      payload.putIfAbsent("channel", "PUSH");
      payload.putIfAbsent("title", "Incident");
      String incidentBody = first(payload, "severity", "message");
      payload.putIfAbsent("body", incidentBody == null ? "Incident update" : incidentBody);
    } else if (type.startsWith("rider.notification.")) {
      payload.putIfAbsent("recipient_type", "RIDER");
      payload.putIfAbsent("title", "Rider update");
      payload.putIfAbsent("body", first(payload, "message", "alert", "template"));
    }
  }

  private static InAppNotificationType inAppType(Map<String, Object> payload) {
    if (payload.get("order_id") != null) {
      return InAppNotificationType.ORDER_UPDATE;
    }
    String template = first(payload, "template", "category");
    if (template != null && template.toLowerCase().contains("refill")) {
      return InAppNotificationType.REFILL_REMINDER;
    }
    return InAppNotificationType.ORDER_UPDATE;
  }

  private static List<String> resolveChannels(Map<String, Object> payload) {
    List<String> out = new ArrayList<>();
    if (payload.get("channels") instanceof List<?> list) {
      for (Object raw : list) {
        String ch = stringVal(raw);
        if (ch != null) {
          out.add(ch.toUpperCase());
        }
      }
    }
    String single = stringVal(payload.get("channel"));
    if (single != null) {
      String up = single.toUpperCase();
      if (!out.contains(up)) {
        out.add(up);
      }
    }
    if (out.isEmpty()
        && stringVal(payload.get("title")) != null
        && first(payload, "body", "message") != null) {
      out.add("PUSH");
    }
    return out;
  }

  private static List<UUID> recipientIds(Map<String, Object> payload, UUID customerId) {
    List<UUID> ids = new ArrayList<>();
    addUuids(ids, payload.get("recipient_ids"));
    addUuids(ids, payload.get("admin_ids"));
    if (customerId != null && !ids.contains(customerId)) {
      ids.add(customerId);
    }
    UUID pharmacyId = parseUuid(payload.get("pharmacy_id"));
    if (pharmacyId != null && !ids.contains(pharmacyId)) {
      ids.add(pharmacyId);
    }
    UUID riderId = parseUuid(payload.get("rider_id"));
    if (riderId != null && !ids.contains(riderId)) {
      ids.add(riderId);
    }
    return ids;
  }

  private static void addUuids(List<UUID> ids, Object raw) {
    if (!(raw instanceof List<?> list)) {
      return;
    }
    for (Object item : list) {
      UUID id = parseUuid(item);
      if (id != null && !ids.contains(id)) {
        ids.add(id);
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> components(Object raw) {
    if (!(raw instanceof List<?> list)) {
      return List.of();
    }
    List<Map<String, Object>> out = new ArrayList<>();
    for (Object item : list) {
      if (item instanceof Map<?, ?> m) {
        out.add(new LinkedHashMap<>((Map<String, Object>) m));
      }
    }
    return out;
  }

  private static Map<String, String> stringVars(Object raw) {
    if (!(raw instanceof Map<?, ?> m)) {
      return Map.of();
    }
    Map<String, String> out = new LinkedHashMap<>();
    for (Map.Entry<?, ?> e : m.entrySet()) {
      out.put(
          String.valueOf(e.getKey()), e.getValue() == null ? null : String.valueOf(e.getValue()));
    }
    return out;
  }

  private static String first(Map<String, Object> payload, String... keys) {
    for (String key : keys) {
      String v = stringVal(payload.get(key));
      if (v != null) {
        return v;
      }
    }
    return null;
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
