package com.nammamedmate.prescription.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class PharmacyRxOverdueSchedulerTest {

  @Test
  void scanDelegates() {
    PharmacyRxQueueService service = mock(PharmacyRxQueueService.class);
    when(service.notifyOverdue()).thenReturn(2);
    new PharmacyRxOverdueScheduler(service).scanOverdue();
    verify(service).notifyOverdue();
  }

  @Test
  void scanSilentWhenZero() {
    PharmacyRxQueueService service = mock(PharmacyRxQueueService.class);
    when(service.notifyOverdue()).thenReturn(0);
    new PharmacyRxOverdueScheduler(service).scanOverdue();
    verify(service).notifyOverdue();
  }
}
