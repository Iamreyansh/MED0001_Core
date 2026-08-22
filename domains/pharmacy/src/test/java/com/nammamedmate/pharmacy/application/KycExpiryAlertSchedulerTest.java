package com.nammamedmate.pharmacy.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class KycExpiryAlertSchedulerTest {

  @Test
  void runDelegatesToDispatch() {
    KycExpiryAlertDispatchService dispatch = mock(KycExpiryAlertDispatchService.class);
    when(dispatch.dispatchDue()).thenReturn(2);
    new KycExpiryAlertScheduler(dispatch).run();
    verify(dispatch).dispatchDue();
  }
}
