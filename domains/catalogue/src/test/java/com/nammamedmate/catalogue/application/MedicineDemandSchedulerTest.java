package com.nammamedmate.catalogue.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

class MedicineDemandSchedulerTest {

  @Test
  void refreshDemand_delegates() {
    MedicineService service = mock(MedicineService.class);
    new MedicineDemandScheduler(service).refreshDemand();
    verify(service).refreshMonthlyDemand();
  }
}
