package com.nammamedmate.crm.adapter.out.messaging;

import com.nammamedmate.crm.application.port.out.CrmLeadOutboxPort;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class OutboxCrmLeadAdapter implements CrmLeadOutboxPort {

  private final OutboxPublisher outbox;

  public OutboxCrmLeadAdapter(OutboxPublisher outbox) {
    this.outbox = outbox;
  }

  @Override
  public void publish(String type, UUID aggregateId, Map<String, Object> payload) {
    outbox.publish(DomainEvent.of(type, "crm_lead", aggregateId, payload));
  }
}
