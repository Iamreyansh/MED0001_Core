package com.nammamedmate.integration.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class FailedPayoutRetrySchedulerTest {

  @Test
  void delegatesToService() {
    RazorpayIntegrationService service = mock(RazorpayIntegrationService.class);
    when(service.retryFailedPayouts()).thenReturn(2);
    new FailedPayoutRetryScheduler(service).retryFailedPayouts();
    verify(service).retryFailedPayouts();
  }
}
