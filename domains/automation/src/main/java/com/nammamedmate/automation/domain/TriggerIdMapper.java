package com.nammamedmate.automation.domain;

import java.util.Optional;

/** Maps outbox/SQS domain event types onto trigger_registry.trigger_id slugs. */
public final class TriggerIdMapper {

  private TriggerIdMapper() {}

  public static Optional<String> fromEventType(String type) {
    if (type == null || type.isBlank()) {
      return Optional.empty();
    }
    String t = type.trim();
    return switch (t) {
      case "order.placed", "order.placed.pharmacy_notified" -> Optional.of("order_placed");
      case "order.delivered" -> Optional.of("order_delivered");
      case "order.cancelled" -> Optional.of("order_cancelled");
      case "order.unassigned", "order.assignment.timed_out", "order.rider.escalation" ->
          Optional.of("order_unassigned");
      case "payment.failed" -> Optional.of("payment_failed");
      case "support.automation.sla_escalate",
          "ticket.sla_breaching",
          "sla_breaching",
          "support.sla_breaching" ->
          Optional.of("support_sla_breaching");
      default -> t.indexOf('.') < 0 ? Optional.of(t) : Optional.empty();
    };
  }
}
