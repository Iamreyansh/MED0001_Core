package com.nammamedmate.observability_ops.adapter.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class OutboxNotificationDispatchAdapterTest {

  @Test
  void publishesWhenOutboxPresent() {
    OutboxPublisher publisher = mock(OutboxPublisher.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<OutboxPublisher> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(publisher);
    OutboxNotificationDispatchAdapter adapter = new OutboxNotificationDispatchAdapter(provider);
    UUID alertId = UUID.randomUUID();
    adapter.pageCritical(alertId, "GMV_DROP", List.of(UUID.randomUUID()));
    verify(publisher).publish(any(DomainEvent.class));
    assertThat(adapter.dispatched()).hasSize(1);
    assertThat(adapter.dispatched().getFirst().channels()).containsExactly("push", "sms");
    assertThat(adapter.dispatched().getFirst().priority()).isEqualTo("HIGH");
    assertThat(adapter.dispatched().getFirst().alertId()).isEqualTo(alertId);
    assertThat(adapter.dispatched().getFirst().alertType()).isEqualTo("GMV_DROP");
    adapter.pageCritical(null, "X", List.of());
    adapter.clearDispatched();
    assertThat(adapter.dispatched()).isEmpty();
  }

  @Test
  void pageIncidentAndRemindPostmortem() {
    OutboxPublisher publisher = mock(OutboxPublisher.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<OutboxPublisher> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(publisher);
    OutboxNotificationDispatchAdapter adapter = new OutboxNotificationDispatchAdapter(provider);
    UUID incidentId = UUID.randomUUID();
    adapter.pageIncident(incidentId, "P1", List.of(UUID.randomUUID()));
    adapter.remindPostmortem(incidentId, List.of(UUID.randomUUID()));
    assertThat(adapter.dispatched()).hasSize(2);
    assertThat(adapter.dispatched().get(0).kind()).isEqualTo("incident");
    assertThat(adapter.dispatched().get(0).alertId()).isNull();
    assertThat(adapter.dispatched().get(0).alertType()).isNull();
    assertThat(adapter.dispatched().get(1).kind()).isEqualTo("postmortem_reminder");
    assertThat(adapter.dispatched().get(1).alertId()).isNull();
    assertThat(adapter.dispatched().get(1).alertType()).isNull();

    adapter.clearDispatched();
    adapter.pageIncident(null, "P2", null);
    adapter.remindPostmortem(null, null);
    verify(publisher, org.mockito.Mockito.times(2)).publish(any(DomainEvent.class));
    assertThat(adapter.dispatched()).hasSize(2);
    assertThat(adapter.dispatched().get(0).adminIds()).isEmpty();
  }

  @Test
  void skipsPublishWhenOutboxMissing() {
    @SuppressWarnings("unchecked")
    ObjectProvider<OutboxPublisher> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(null);
    OutboxPublisher publisher = mock(OutboxPublisher.class);
    OutboxNotificationDispatchAdapter adapter = new OutboxNotificationDispatchAdapter(provider);
    adapter.pageIncident(UUID.randomUUID(), "P1", List.of());
    adapter.remindPostmortem(UUID.randomUUID(), List.of());
    verify(publisher, never()).publish(any());
    assertThat(adapter.dispatched()).hasSize(2);
  }
}
