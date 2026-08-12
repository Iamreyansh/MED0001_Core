package com.nammamedmate.automation.adapter.out.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.automation.application.port.out.ActivityLogPort;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StubExecutorTest {

  @Test
  void stubDispatchesAndLogs() {
    ActivityLogPort log = mock(ActivityLogPort.class);
    when(log.append(any(), any(), any(), any())).thenReturn(UUID.randomUUID());
    StubActionExecutor exec = new StubActionExecutor(log);
    assertThat(exec.execute("flag_prescription", Map.of("prescription_id", "x"), Map.of()))
        .isNotNull();
    assertThat(exec.execute("suspend_entity", Map.of(), null)).isNotNull();
    assertThat(exec.execute("apply_wallet_credit", null, Map.of("entity_type", "CUSTOMER")))
        .isNotNull();
    assertThat(exec.execute("auto_assign_rider", Map.of(), Map.of())).isNotNull();
    assertThat(exec.execute("reactivate_entity", Map.of(), Map.of())).isNotNull();
    assertThat(
            exec.execute(
                "flag_prescription",
                Map.of(),
                Map.of("entity_type", "RX", "entity_id", UUID.randomUUID())))
        .isNotNull();
    assertThat(exec.execute(null, Map.of(), Map.of("entity_type", "ORDER"))).isNotNull();
    verify(log).append(eq("suspend_entity"), eq("EXECUTED"), anyString(), anyMap());
  }
}
