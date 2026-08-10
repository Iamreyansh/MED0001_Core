package com.nammamedmate.teleconsult.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class ConsultAutoCancelSchedulerTest {

  @Test
  void logsWhenCancelled() {
    ConsultService service = mock(ConsultService.class);
    when(service.autoCancelOverdue()).thenReturn(2);
    new ConsultAutoCancelScheduler(service).autoCancelOverdue();
    verify(service).autoCancelOverdue();
  }

  @Test
  void silentWhenZero() {
    ConsultService service = mock(ConsultService.class);
    when(service.autoCancelOverdue()).thenReturn(0);
    new ConsultAutoCancelScheduler(service).autoCancelOverdue();
    verify(service).autoCancelOverdue();
  }
}
