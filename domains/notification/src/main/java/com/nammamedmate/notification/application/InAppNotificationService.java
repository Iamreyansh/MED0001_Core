package com.nammamedmate.notification.application;

import com.nammamedmate.kernel.api.PageRequest;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.notification.application.port.out.DispatchLogStore;
import com.nammamedmate.notification.application.port.out.InAppNotificationStore;
import com.nammamedmate.notification.application.port.out.RecipientDisplayNamePort;
import com.nammamedmate.notification.domain.DispatchLogEntry;
import com.nammamedmate.notification.domain.InAppNotification;
import com.nammamedmate.notification.domain.InAppNotificationType;
import com.nammamedmate.notification.domain.NotificationUserType;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class InAppNotificationService {

  private final InAppNotificationStore store;
  private final DispatchLogStore dispatchLogs;
  private final RecipientDisplayNamePort displayNames;
  private final Clock clock;

  public InAppNotificationService(
      InAppNotificationStore store,
      DispatchLogStore dispatchLogs,
      RecipientDisplayNamePort displayNames,
      Clock clock) {
    this.store = store;
    this.dispatchLogs = dispatchLogs;
    this.displayNames = displayNames;
    this.clock = clock;
  }

  public InAppNotification createOrderUpdate(
      UUID customerId, String title, String body, String actionUrl) {
    return create(customerId, InAppNotificationType.ORDER_UPDATE, title, body, actionUrl);
  }

  public InAppNotification create(
      UUID customerId, InAppNotificationType type, String title, String body, String actionUrl) {
    if (customerId == null) {
      throw new AppException("VALIDATION_ERROR", "customer_id required", 400);
    }
    if (title == null || title.isBlank() || body == null || body.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "title and body required", 400);
    }
    Instant now = clock.instant();
    InAppNotification n =
        new InAppNotification(
            Ids.newId(),
            customerId,
            type,
            title.trim(),
            body.trim(),
            blankToNull(actionUrl),
            false,
            false,
            null,
            now.plus(Duration.ofDays(type.retentionDays())),
            now);
    store.insert(n);
    return n;
  }

  public HistoryPage list(
      UUID customerId, Boolean unreadOnly, String type, Integer page, Integer limit) {
    PageRequest pr = PageRequest.normalize(page, limit, null, null);
    InAppNotificationType parsed = type == null || type.isBlank() ? null : parseType(type);
    Instant now = clock.instant();
    InAppNotificationStore.Page result =
        store.list(
            new InAppNotificationStore.ListFilter(
                customerId, Boolean.TRUE.equals(unreadOnly), parsed, now, pr.page(), pr.limit()));
    List<Map<String, Object>> items = new ArrayList<>(result.items().size());
    for (InAppNotification n : result.items()) {
      items.add(toInboxItem(n));
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("notifications", items);
    return new HistoryPage(data, pr.page(), pr.limit(), result.total());
  }

  public Map<String, Object> unreadCount(UUID customerId) {
    long count = store.countUnread(customerId, clock.instant());
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("unread_count", count);
    return data;
  }

  public Map<String, Object> markRead(UUID customerId, UUID id) {
    Instant now = clock.instant();
    if (!store.markRead(id, customerId, now)) {
      throw notFound();
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", id.toString());
    data.put("is_read", true);
    data.put("read_at", now.toString());
    return data;
  }

  public Map<String, Object> markAllRead(UUID customerId, Boolean markAllRead) {
    if (!Boolean.TRUE.equals(markAllRead)) {
      throw new AppException("VALIDATION_ERROR", "mark_all_read must be true", 400);
    }
    Instant now = clock.instant();
    int updated = store.markAllRead(customerId, now, now);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("marked_read_count", updated);
    data.put("updated_at", now.toString());
    return data;
  }

  public Map<String, Object> delete(UUID customerId, UUID id) {
    InAppNotification n =
        store.findByIdForCustomer(id, customerId).orElseThrow(InAppNotificationService::notFound);
    if (n.type() == InAppNotificationType.ORDER_UPDATE) {
      throw new AppException(
          "CANNOT_DELETE_ORDER_UPDATE", "ORDER_UPDATE notifications cannot be deleted", 422);
    }
    if (!n.type().canDelete()) {
      throw new AppException("CANNOT_DELETE", "Notification type cannot be deleted", 422);
    }
    store.softDelete(id, customerId);
    return Map.of("deleted", true);
  }

  public HistoryPage adminHistory(
      String channel,
      String status,
      String recipientType,
      Instant dateFrom,
      Instant dateTo,
      String export,
      Integer page,
      Integer limit) {
    PageRequest pr = PageRequest.normalize(page, limit, null, null);
    String ch = normalizeUpper(channel);
    String st = normalizeUpper(status);
    String rt = normalizeUpper(recipientType);
    if (ch != null && !List.of("PUSH", "SMS", "WHATSAPP", "EMAIL").contains(ch)) {
      throw new AppException("VALIDATION_ERROR", "Invalid channel", 400);
    }
    DispatchLogStore.Page result =
        dispatchLogs.list(
            new DispatchLogStore.ListFilter(ch, st, rt, dateFrom, dateTo, pr.page(), pr.limit()));
    List<Map<String, Object>> history = new ArrayList<>(result.items().size());
    for (DispatchLogEntry e : result.items()) {
      history.add(toHistoryItem(e));
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("history", history);
    if (export != null && "csv".equalsIgnoreCase(export.trim())) {
      // ponytail: data-URL stub until S3 PutObject + presigned GET for large admin exports.
      data.put("export_url", toCsvDataUrl(history));
    } else {
      data.put("export_url", null);
    }
    return new HistoryPage(data, pr.page(), pr.limit(), result.total());
  }

  public int runTtlCleanup() {
    Instant now = clock.instant();
    int soft = store.softDeleteExpired(now);
    Instant hardCutoff = now.minus(Duration.ofDays(30));
    int hard = store.hardDeletePastRetention(hardCutoff);
    return soft + hard;
  }

  private Map<String, Object> toInboxItem(InAppNotification n) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", n.id().toString());
    m.put("type", n.type().name());
    m.put("title", n.title());
    m.put("body", n.body());
    m.put("action_url", n.actionUrl());
    m.put("is_read", n.read());
    m.put("can_delete", n.type().canDelete());
    m.put("created_at", n.createdAt().toString());
    return m;
  }

  private Map<String, Object> toHistoryItem(DispatchLogEntry e) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("dispatch_id", e.dispatchId().toString());
    m.put("recipient_id", e.recipientId() == null ? null : e.recipientId().toString());
    String name = null;
    if (e.recipientId() != null && e.recipientType() != null) {
      try {
        name =
            displayNames
                .displayName(e.recipientId(), NotificationUserType.parse(e.recipientType()))
                .orElse(null);
      } catch (IllegalArgumentException ignored) {
        name = null;
      }
    }
    m.put("recipient_name", name);
    m.put("recipient_type", e.recipientType());
    m.put("channel", e.channel());
    m.put("type", e.type());
    m.put("title", e.title());
    m.put("status", e.status());
    m.put("sent_at", e.sentAt().toString());
    m.put("delivered_at", e.deliveredAt() == null ? null : e.deliveredAt().toString());
    return m;
  }

  private static String toCsvDataUrl(List<Map<String, Object>> history) {
    StringBuilder sb = new StringBuilder();
    sb.append(
        "dispatch_id,recipient_id,recipient_name,recipient_type,channel,type,title,status,sent_at,delivered_at\n");
    for (Map<String, Object> row : history) {
      sb.append(csv(row.get("dispatch_id")))
          .append(',')
          .append(csv(row.get("recipient_id")))
          .append(',')
          .append(csv(row.get("recipient_name")))
          .append(',')
          .append(csv(row.get("recipient_type")))
          .append(',')
          .append(csv(row.get("channel")))
          .append(',')
          .append(csv(row.get("type")))
          .append(',')
          .append(csv(row.get("title")))
          .append(',')
          .append(csv(row.get("status")))
          .append(',')
          .append(csv(row.get("sent_at")))
          .append(',')
          .append(csv(row.get("delivered_at")))
          .append('\n');
    }
    String b64 = Base64.getEncoder().encodeToString(sb.toString().getBytes(StandardCharsets.UTF_8));
    return "data:text/csv;base64," + b64;
  }

  private static String csv(Object value) {
    if (value == null) {
      return "";
    }
    // always quote — avoids multi-branch delimiter detection for JaCoCo + correctness
    return "\"" + String.valueOf(value).replace("\"", "\"\"") + "\"";
  }

  private static InAppNotificationType parseType(String type) {
    try {
      return InAppNotificationType.parse(type);
    } catch (IllegalArgumentException e) {
      throw new AppException("VALIDATION_ERROR", "Invalid notification type", 400);
    }
  }

  private static String normalizeUpper(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    return raw.trim().toUpperCase(Locale.ROOT);
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }

  private static AppException notFound() {
    return new AppException("NOTIFICATION_NOT_FOUND", "Notification not found", 404);
  }

  public record HistoryPage(Map<String, Object> data, int page, int limit, long total) {}
}
