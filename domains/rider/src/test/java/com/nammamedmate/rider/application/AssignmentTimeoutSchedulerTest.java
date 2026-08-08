package com.nammamedmate.rider.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class AssignmentTimeoutSchedulerTest {

  @Test
  void delegatesToDispatchService() {
    DispatchService dispatch = mock(DispatchService.class);
    when(dispatch.timeoutExpiredAssignments()).thenReturn(2);
    new AssignmentTimeoutScheduler(dispatch).timeoutExpired();
    verify(dispatch).timeoutExpiredAssignments();
  }
}
