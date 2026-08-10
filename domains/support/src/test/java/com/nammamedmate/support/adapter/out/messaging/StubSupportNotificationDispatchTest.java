package com.nammamedmate.support.adapter.out.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.nammamedmate.messaging.OutboxPublisher;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StubSupportNotificationDispatchTest {

  @Test
  void publishesOutboxEvents() {
    OutboxPublisher outbox = mock(OutboxPublisher.class);
    StubSupportNotificationDispatch stub = new StubSupportNotificationDispatch(outbox);
    UUID ticket = UUID.randomUUID();
    UUID customer = UUID.randomUUID();
    stub.notifyEscalation(ticket, customer, "L4");
    stub.notifyCsatSurvey(ticket, customer, "APP");
    stub.notifySupervisorEscalation(ticket, "reason");
    verify(outbox, times(3)).publish(any());
    // null customer_id branch in payload
    stub.notifyEscalation(ticket, null, "L3");
    verify(outbox, times(4)).publish(any());
    stub.notifyEscalationChannels(
        ticket, customer, "L4", "Senior Ops Manager", List.of("IN_APP", "WHATSAPP", "CALL"));
    verify(outbox, times(5)).publish(any());
    stub.notifyEscalationChannels(ticket, customer, "L4", "Senior Ops Manager", null);
    verify(outbox, times(6)).publish(any());
    stub.notifyWeeklyAgentPerformance(java.time.LocalDate.parse("2026-07-13"), 3);
    verify(outbox, times(7)).publish(any());
    stub.notifyWeeklyAgentPerformance(null, 0);
    verify(outbox, times(8)).publish(any());
  }
}
