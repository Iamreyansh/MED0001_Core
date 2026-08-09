package com.nammamedmate.integration.adapter.out.messaging;

import com.nammamedmate.integration.application.port.out.IntegrationEventPort;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Publishes ids-only integration events via outbox when {@link OutboxPublisher} is present
 * (apps/api); otherwise no-ops (local domain tests without messaging composition).
 */
@Component
public class OutboxIntegrationEventAdapter implements IntegrationEventPort {

  private final ObjectProvider<OutboxPublisher> outbox;

  public OutboxIntegrationEventAdapter(ObjectProvider<OutboxPublisher> outbox) {
    this.outbox = outbox;
  }

  @Override
  public void publish(
      String type, String aggregateType, UUID aggregateId, Map<String, Object> payload) {
    OutboxPublisher publisher = outbox.getIfAvailable();
    if (publisher == null) {
      return;
    }
    publisher.publish(DomainEvent.of(type, aggregateType, aggregateId, payload));
  }
}
