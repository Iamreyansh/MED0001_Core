package com.nammamedmate.customer.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import org.junit.jupiter.api.Test;

class CustomerMaintenanceSchedulerTest {

  @Test
  void runNightlyJobs_invokesSegmentRecomputeAndAnonymise() {
    CustomerMaintenanceService maintenance = mock(CustomerMaintenanceService.class);
    CustomerMaintenanceScheduler scheduler = new CustomerMaintenanceScheduler(maintenance);

    scheduler.runNightlyJobs();

    verify(maintenance).recomputeSegments();
    verify(maintenance).anonymiseDueAccounts();
    verifyNoMoreInteractions(maintenance);
  }
}
