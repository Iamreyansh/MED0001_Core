package com.nammamedmate.crm.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

class InvoiceDunningSchedulerTest {

  @Test
  void runDelegates() {
    SaasBillingService billing = mock(SaasBillingService.class);
    new InvoiceDunningScheduler(billing).run();
    verify(billing).processDunningJobs();
  }
}
