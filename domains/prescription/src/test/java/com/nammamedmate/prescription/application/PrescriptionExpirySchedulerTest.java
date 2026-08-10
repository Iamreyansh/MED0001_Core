package com.nammamedmate.prescription.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class PrescriptionExpirySchedulerTest {

  @Test
  void runsExpireDue() {
    PrescriptionService service = mock(PrescriptionService.class);
    when(service.expireDue()).thenReturn(3);
    new PrescriptionExpiryScheduler(service).expireDuePrescriptions();
    verify(service).expireDue();
  }

  @Test
  void zeroExpired_stillCalls() {
    PrescriptionService service = mock(PrescriptionService.class);
    when(service.expireDue()).thenReturn(0);
    new PrescriptionExpiryScheduler(service).expireDuePrescriptions();
    verify(service).expireDue();
  }
}
