package com.nammamedmate.automation.adapter.out.executor;

import com.nammamedmate.automation.application.port.out.ActionExecutorPort;
import com.nammamedmate.automation.application.port.out.ActivityLogPort;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Persists activity and publishes a real side-effect outbox event per action. */
public class OutboxActionExecutor implements ActionExecutorPort {

  private final ActivityLogPort activityLog;
  private final OutboxPublisher outbox;

  public OutboxActionExecutor(ActivityLogPort activityLog, OutboxPublisher outbox) {
    this.activityLog = activityLog;
    this.outbox = outbox;
  }

  @Override
  public UUID execute(String actionId, Map<String, Object> params, Map<String, Object> context) {
    Map<String, Object> p = params == null ? Map.of() : params;
    Map<String, Object> ctx = context == null ? Map.of() : context;
    Map<String, Object> detail = new LinkedHashMap<>(ctx);
    detail.put("params", p);
    detail.putIfAbsent("actor", "AUTOMATION");
    UUID activityId =
        activityLog.append(actionId == null ? "" : actionId, "EXECUTED", "executed", detail);
    String type = eventType(actionId);
    Map<String, Object> payload = payloadFor(actionId, p, ctx, activityId);
    outbox.publish(
        DomainEvent.of(
            type, aggregateType(actionId, ctx), aggregateId(p, ctx, activityId), payload));
    return activityId;
  }

  static String eventType(String actionId) {
    if (actionId == null) {
      return "automation.action.executed";
    }
    return switch (actionId) {
      case "send_notification" -> "customer.notification.requested";
      case "page_human" -> "observability.alert.critical_page";
      case "update_order_status" -> "automation.order.status_update_requested";
      case "process_refund" -> "order.refund.requested";
      case "auto_assign_rider", "auto_reassign_rider" -> "automation.rider.assign_requested";
      default -> "automation.action.executed";
    };
  }

  private static String aggregateType(String actionId, Map<String, Object> ctx) {
    if ("send_notification".equals(actionId)) {
      return "customer";
    }
    if ("page_human".equals(actionId)) {
      return "monitoring_alert";
    }
    Object entityType = ctx.get("entity_type");
    if (entityType != null && !String.valueOf(entityType).isBlank()) {
      return String.valueOf(entityType).toLowerCase();
    }
    return "automation";
  }

  private static UUID aggregateId(
      Map<String, Object> params, Map<String, Object> ctx, UUID fallback) {
    UUID id = asUuid(params.get("order_id"));
    if (id == null) {
      id = asUuid(params.get("refund_id"));
    }
    if (id == null) {
      id = asUuid(params.get("recipient_id"));
    }
    if (id == null) {
      id = asUuid(ctx.get("entity_id"));
    }
    return id == null ? fallback : id;
  }

  private static Map<String, Object> payloadFor(
      String actionId, Map<String, Object> params, Map<String, Object> ctx, UUID activityId) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("activity_id", activityId.toString());
    payload.put("action_id", actionId == null ? "" : actionId);
    if ("send_notification".equals(actionId)) {
      payload.put("channel", str(params.get("channel"), "PUSH"));
      payload.put("template_id", str(params.get("template_id"), ""));
      UUID recipient = asUuid(params.get("recipient_id"));
      if (recipient == null) {
        recipient = asUuid(params.get("customer_id"));
      }
      if (recipient == null) {
        recipient = asUuid(ctx.get("entity_id"));
      }
      if (recipient != null) {
        payload.put("customer_id", recipient.toString());
      }
      payload.put("title", str(nested(params, "title"), "Notification"));
      payload.put(
          "body", str(nested(params, "body"), str(params.get("message"), "Automation notice")));
      UUID orderId = asUuid(params.get("order_id"));
      if (orderId == null && "ORDER".equalsIgnoreCase(str(ctx.get("entity_type"), ""))) {
        orderId = asUuid(ctx.get("entity_id"));
      }
      if (orderId != null) {
        payload.put("order_id", orderId.toString());
      }
      return payload;
    }
    if ("page_human".equals(actionId)) {
      payload.put("alert_id", activityId.toString());
      payload.put("alert_type", str(params.get("severity"), "CRITICAL"));
      payload.put("message", str(params.get("message"), "Automation page"));
      payload.put("channels", List.of(str(params.get("channel"), "push")));
      payload.put("priority", "HIGH");
      payload.put("roles", List.of("admin_super", "admin_operations"));
      return payload;
    }
    if ("update_order_status".equals(actionId)) {
      payload.put("order_id", str(params.get("order_id"), str(ctx.get("entity_id"), "")));
      payload.put("status", str(params.get("status"), ""));
      payload.put("reason", str(params.get("reason"), "automation"));
      return payload;
    }
    if ("process_refund".equals(actionId)) {
      payload.put("refund_id", str(params.get("refund_id"), ""));
      payload.put("order_id", str(params.get("order_id"), str(ctx.get("entity_id"), "")));
      Object amount = params.get("amount_paise");
      if (amount == null) {
        amount = ctx.get("amount_paise");
      }
      if (amount != null) {
        payload.put("amount_paise", amount);
      }
      payload.put("reason", str(params.get("reason"), "automation"));
      return payload;
    }
    if ("auto_assign_rider".equals(actionId) || "auto_reassign_rider".equals(actionId)) {
      payload.put("order_id", str(params.get("order_id"), str(ctx.get("entity_id"), "")));
      if (params.get("exclude_rider_id") != null) {
        payload.put("exclude_rider_id", params.get("exclude_rider_id"));
      }
      return payload;
    }
    payload.put("params", params);
    payload.put("context", ctx);
    return payload;
  }

  private static Object nested(Map<String, Object> params, String key) {
    if (params.get(key) != null) {
      return params.get(key);
    }
    Object inner = params.get("payload");
    if (inner instanceof Map<?, ?> m) {
      return m.get(key);
    }
    return null;
  }

  private static String str(Object raw, String fallback) {
    if (raw == null) {
      return fallback;
    }
    String s = String.valueOf(raw).trim();
    return s.isEmpty() ? fallback : s;
  }

  private static UUID asUuid(Object raw) {
    if (raw == null) {
      return null;
    }
    if (raw instanceof UUID u) {
      return u;
    }
    try {
      return UUID.fromString(String.valueOf(raw));
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }
}
