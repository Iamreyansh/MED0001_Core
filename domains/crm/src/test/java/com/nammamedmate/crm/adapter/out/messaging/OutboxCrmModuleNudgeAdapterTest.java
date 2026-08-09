package com.nammamedmate.crm.adapter.out.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OutboxCrmModuleNudgeAdapterTest {

  @Mock OutboxPublisher outbox;

  @Test
  void publishes() {
    new OutboxCrmModuleNudgeAdapter(outbox)
        .publish("crm.module.nudge", Ids.newId(), Map.of("account_ids", List.of(Ids.newId())));
    verify(outbox).publish(any(DomainEvent.class));
  }
}
