package com.nammamedmate.notification.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class InAppNotificationTtlSchedulerTest {

  @Test
  void cleanupDelegates() {
    InAppNotificationService service = mock(InAppNotificationService.class);
    when(service.runTtlCleanup()).thenReturn(2);
    new InAppNotificationTtlScheduler(service).cleanup();
    verify(service).runTtlCleanup();
  }
}
