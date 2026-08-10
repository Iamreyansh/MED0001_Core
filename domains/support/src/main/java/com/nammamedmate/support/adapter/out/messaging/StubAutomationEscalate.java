package com.nammamedmate.support.adapter.out.messaging;

import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.support.application.port.out.AutomationEscalatePort;
import com.nammamedmate.support.application.port.out.NotificationDispatchPort;
import com.nammamedmate.support.application.port.out.TicketStore;
import com.nammamedmate.support.domain.SlaLevel;
import com.nammamedmate.support.domain.Ticket;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Stub automation: bump SLA level on ticket + outbox + channel notify. */
public final class StubAutomationEscalate implements AutomationEscalatePort {

  private final TicketStore tickets;
  private final NotificationDispatchPort notifications;
  private final OutboxPublisher outbox;
  private final Clock clock;

  public StubAutomationEscalate(
      TicketStore tickets,
      NotificationDispatchPort notifications,
      OutboxPublisher outbox,
      Clock clock) {
    this.tickets = tickets;
    this.notifications = notifications;
    this.outbox = outbox;
    this.clock = clock;
  }

  public StubAutomationEscalate(
      TicketStore tickets, NotificationDispatchPort notifications, Clock clock) {
    this(tickets, notifications, null, clock);
  }

  @Override
  public void escalateOnSlaBreach(UUID ticketId, String fromLevel, String toLevel) {
    Instant now = clock.instant();
    Ticket ticket = tickets.findById(ticketId).orElse(null);
    if (ticket == null) {
      return;
    }
    SlaLevel to = SlaLevel.valueOf(toLevel);
    tickets.update(ticket.withSlaLevel(to, now));
    notifications.notifyEscalation(ticketId, ticket.customerId(), toLevel);
    if (outbox != null) {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("ticket_id", ticketId.toString());
      payload.put("from_level", fromLevel);
      payload.put("to_level", toLevel);
      outbox.publish(
          DomainEvent.of("support.automation.sla_escalate", "support_ticket", ticketId, payload));
    }
  }

  @Override
  public void notifyL4SeniorOps(UUID ticketId, String team, List<String> channels) {
    Instant now = clock.instant();
    Ticket ticket = tickets.findById(ticketId).orElse(null);
    if (ticket == null) {
      return;
    }
    tickets.update(ticket.withL4Notified(now));
    notifications.notifyEscalationChannels(
        ticketId, ticket.customerId(), SlaLevel.L4.name(), team, channels);
    if (outbox != null) {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("ticket_id", ticketId.toString());
      payload.put("team", team);
      payload.put("channels", channels);
      payload.put("level", "L4");
      outbox.publish(
          DomainEvent.of("support.automation.l4_senior_ops", "support_ticket", ticketId, payload));
    }
  }
}
