package com.nammamedmate.support.adapter.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.support.application.port.out.NotificationDispatchPort;
import com.nammamedmate.support.application.port.out.TicketStore;
import com.nammamedmate.support.domain.SlaLevel;
import com.nammamedmate.support.domain.Ticket;
import com.nammamedmate.support.domain.TicketCategory;
import com.nammamedmate.support.domain.TicketChannel;
import com.nammamedmate.support.domain.TicketPriority;
import com.nammamedmate.support.domain.TicketStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class StubAutomationEscalateTest {

  @Test
  void bumpsLevelAndNotifiesL4() {
    Instant now = Instant.parse("2026-07-24T10:00:00Z");
    UUID id = UUID.randomUUID();
    Instant due = now.plusSeconds(1800);
    Ticket t =
        new Ticket(
            id,
            "TKT-20260724-000001",
            UUID.randomUUID(),
            null,
            null,
            TicketCategory.ORDER,
            "s",
            TicketStatus.OPEN,
            TicketPriority.HIGH,
            SlaLevel.L1,
            due,
            due,
            due.plusSeconds(3600),
            null,
            TicketChannel.APP,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            now,
            now);
    AtomicReference<Ticket> held = new AtomicReference<>(t);
    TicketStore store = mock(TicketStore.class);
    when(store.findById(id)).thenAnswer(inv -> Optional.ofNullable(held.get()));
    when(store.findById(org.mockito.ArgumentMatchers.argThat(u -> !id.equals(u))))
        .thenReturn(Optional.empty());
    org.mockito.Mockito.doAnswer(
            inv -> {
              held.set(inv.getArgument(0));
              return null;
            })
        .when(store)
        .update(any());
    NotificationDispatchPort notifications = mock(NotificationDispatchPort.class);
    OutboxPublisher outbox = mock(OutboxPublisher.class);
    StubAutomationEscalate stub =
        new StubAutomationEscalate(store, notifications, outbox, Clock.fixed(now, ZoneOffset.UTC));
    stub.escalateOnSlaBreach(id, "L1", "L2");
    assertThat(held.get().slaLevel()).isEqualTo(SlaLevel.L2);
    stub.notifyL4SeniorOps(id, "Senior Ops Manager", List.of("IN_APP", "WHATSAPP", "CALL"));
    assertThat(held.get().slaL4NotifiedAt()).isEqualTo(now);
    verify(outbox, times(2)).publish(any());
    stub.escalateOnSlaBreach(UUID.randomUUID(), "L1", "L2");
    stub.notifyL4SeniorOps(UUID.randomUUID(), "t", List.of());
    new StubAutomationEscalate(store, notifications, Clock.fixed(now, ZoneOffset.UTC))
        .escalateOnSlaBreach(id, "L2", "L3");
  }
}
