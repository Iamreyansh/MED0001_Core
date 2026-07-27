package com.nammamedmate.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

class AutoKycMaintenanceSchedulerTest {

  @Test
  void runsRetryAndStaleJobs() {
    AutoKycRetryWorker retryWorker = mock(AutoKycRetryWorker.class);
    AutoKycService autoKyc = mock(AutoKycService.class);
    AutoKycMaintenanceScheduler scheduler = new AutoKycMaintenanceScheduler(retryWorker, autoKyc);

    scheduler.runRetryAndTimeoutJobs();

    verify(retryWorker).processDueRetries();
    verify(autoKyc).processStaleAsyncChecks();
    assertThat(scheduler).isNotNull();
  }
}
