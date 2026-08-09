package com.nammamedmate.integration.application;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

class CommunicationSchedulersTest {

  @Test
  void healthCheckSchedulerDelegatesAndSwallows() {
    CommunicationService service = mock(CommunicationService.class);
    new CommunicationHealthCheckScheduler(service).tick();
    verify(service).runHealthChecks();
    doThrow(new RuntimeException("down")).when(service).runHealthChecks();
    new CommunicationHealthCheckScheduler(service).tick();
  }

  @Test
  void dailyResetSchedulerDelegates() {
    CommunicationService service = mock(CommunicationService.class);
    new CommunicationDailyResetScheduler(service).midnightReset();
    verify(service).resetDailySentCounts();
  }
}
