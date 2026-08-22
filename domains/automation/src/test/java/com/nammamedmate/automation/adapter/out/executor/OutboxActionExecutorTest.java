package com.nammamedmate.automation.adapter.out.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.automation.application.port.out.ActivityLogPort;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OutboxActionExecutorTest {

  @Test
  void appendsActivityAndPublishes() {
    ActivityLogPort log = mock(ActivityLogPort.class);
    OutboxPublisher outbox = mock(OutboxPublisher.class);
    UUID id = UUID.randomUUID();
    when(log.append(any(), any(), any(), any())).thenReturn(id);
    OutboxActionExecutor exec = new OutboxActionExecutor(log, outbox);
    assertThat(exec.execute("send_notification", Map.of("k", "v"), null)).isEqualTo(id);
    verify(outbox).publish(any(DomainEvent.class));
    assertThat(exec.execute(null, null, Map.of("entity_type", "ORDER"))).isEqualTo(id);
  }
}
