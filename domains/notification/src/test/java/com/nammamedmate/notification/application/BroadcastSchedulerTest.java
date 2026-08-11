package com.nammamedmate.notification.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class BroadcastSchedulerTest {

  @Test
  void processQueuedDelegates() {
    BroadcastService svc = mock(BroadcastService.class);
    when(svc.processDue(50)).thenReturn(2);
    new BroadcastScheduler(svc).processQueued();
    verify(svc).processDue(50);
  }
}
