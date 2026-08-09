package com.nammamedmate.integration.adapter.out.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class OutboxIntegrationEventAdapterTest {

  @Test
  @SuppressWarnings("unchecked")
  void publishesWhenOutboxPresent() {
    OutboxPublisher outbox = mock(OutboxPublisher.class);
    ObjectProvider<OutboxPublisher> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(outbox);
    OutboxIntegrationEventAdapter adapter = new OutboxIntegrationEventAdapter(provider);
    UUID id = UUID.randomUUID();
    adapter.publish("PAYMENT_CAPTURED", "razorpay_payment", id, Map.of("k", "v"));
    verify(outbox).publish(any(DomainEvent.class));
  }

  @Test
  @SuppressWarnings("unchecked")
  void noopsWhenOutboxMissing() {
    ObjectProvider<OutboxPublisher> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(null);
    OutboxIntegrationEventAdapter adapter = new OutboxIntegrationEventAdapter(provider);
    adapter.publish("X", "y", UUID.randomUUID(), Map.of());
    // no exception
    verify(provider).getIfAvailable();
  }
}
