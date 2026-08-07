package com.nammamedmate.catalogue.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

class PriceCeilingViolationSchedulerTest {

  @Test
  void detectViolations_delegates() {
    PriceCeilingService service = mock(PriceCeilingService.class);
    new PriceCeilingViolationScheduler(service).detectViolations();
    verify(service).detectViolations();
  }
}
