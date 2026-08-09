package com.nammamedmate.payment.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

class TaxFilingSchedulerTest {

  @Test
  void maintainFilingsDelegates() {
    TaxFacadeService taxes = mock(TaxFacadeService.class);
    new TaxFilingScheduler(taxes).maintainFilings();
    verify(taxes).runScheduledMaintenance();
  }
}
