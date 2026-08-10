package com.nammamedmate.support.adapter.out.messaging;

import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.support.application.port.out.NotificationDispatchPort;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** ponytail: outbox ids-only until EPIC-017 notification worker delivers. */
public final class StubSupportNotificationDispatch implements NotificationDispatchPort {

  private final OutboxPublisher outbox;

  public StubSupportNotificationDispatch(OutboxPublisher outbox) {
    this.outbox = outbox;
  }

  @Override
  public void notifyEscalation(UUID ticketId, UUID customerId, String slaLevel) {
    Map<String, Object> payload = base(ticketId, customerId);
    payload.put("template", "SUPPORT_TICKET_ESCALATED");
    payload.put("sla_level", slaLevel);
    outbox.publish(
        DomainEvent.of("support.notification.escalated", "support_ticket", ticketId, payload));
  }

  @Override
  public void notifyCsatSurvey(UUID ticketId, UUID customerId, String channel) {
    Map<String, Object> payload = base(ticketId, customerId);
    payload.put("template", "SUPPORT_CSAT_SURVEY");
    payload.put("channel", channel);
    outbox.publish(
        DomainEvent.of("support.notification.csat_survey", "support_ticket", ticketId, payload));
  }

  @Override
  public void notifySupervisorEscalation(UUID ticketId, String reason) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("notification_id", Ids.newId().toString());
    payload.put("ticket_id", ticketId.toString());
    payload.put("template", "SUPPORT_SUPERVISOR_ESCALATION");
    payload.put("reason", reason);
    payload.put("channels", List.of("IN_APP", "EMAIL"));
    outbox.publish(
        DomainEvent.of(
            "support.notification.supervisor_escalation", "support_ticket", ticketId, payload));
  }

  @Override
  public void notifyEscalationChannels(
      UUID ticketId, UUID customerId, String slaLevel, String team, List<String> channels) {
    Map<String, Object> payload = base(ticketId, customerId);
    payload.put("template", "SUPPORT_TICKET_ESCALATED");
    payload.put("sla_level", slaLevel);
    payload.put("team", team);
    payload.put("channels", channels == null ? List.of() : List.copyOf(channels));
    outbox.publish(
        DomainEvent.of("support.notification.escalated", "support_ticket", ticketId, payload));
  }

  @Override
  public void notifyWeeklyAgentPerformance(LocalDate weekStart, int agentCount) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("notification_id", Ids.newId().toString());
    payload.put("template", "SUPPORT_AGENT_WEEKLY_PERFORMANCE");
    payload.put("week_start", weekStart == null ? "" : weekStart.toString());
    payload.put("agent_count", agentCount);
    payload.put("channels", List.of("EMAIL"));
    outbox.publish(
        DomainEvent.of(
            "support.notification.agent_weekly_performance",
            "support_agent",
            Ids.newId(),
            payload));
  }

  private static Map<String, Object> base(UUID ticketId, UUID customerId) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("notification_id", Ids.newId().toString());
    payload.put("ticket_id", ticketId.toString());
    if (customerId != null) {
      payload.put("customer_id", customerId.toString());
    }
    payload.put("channels", List.of("IN_APP", "EMAIL", "PUSH"));
    return payload;
  }
}
