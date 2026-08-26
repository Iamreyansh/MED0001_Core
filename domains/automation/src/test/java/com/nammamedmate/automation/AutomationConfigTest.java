package com.nammamedmate.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.automation.application.port.out.ActionExecutorPort;
import com.nammamedmate.automation.application.port.out.ActivityLogPort;
import com.nammamedmate.automation.domain.ConditionEvaluator;
import com.nammamedmate.messaging.OutboxPublisher;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AutomationConfigTest {

  @Test
  void beans() {
    AutomationConfig cfg = new AutomationConfig();
    Clock clock = cfg.automationClock();
    assertThat(clock).isNotNull();
    ConditionEvaluator evaluator = cfg.conditionEvaluator(clock);
    assertThat(evaluator).isNotNull();
    ActivityLogPort log = mock(ActivityLogPort.class);
    when(log.append(anyString(), anyString(), anyString(), anyMap())).thenReturn(UUID.randomUUID());
    when(log.append(anyString(), anyString(), anyString(), any())).thenReturn(UUID.randomUUID());
    ActionExecutorPort exec = cfg.stubActionExecutorPort(log);
    assertThat(exec.execute("auto_assign_rider", Map.of(), Map.of())).isNotNull();
    OutboxPublisher outbox = mock(OutboxPublisher.class);
    ActionExecutorPort live = cfg.outboxActionExecutorPort(log, outbox);
    assertThat(live.execute("send_notification", Map.of(), Map.of())).isNotNull();
  }
}
