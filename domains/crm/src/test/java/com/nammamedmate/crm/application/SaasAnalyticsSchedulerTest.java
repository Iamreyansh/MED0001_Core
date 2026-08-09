package com.nammamedmate.crm.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

class SaasAnalyticsSchedulerTest {

  @Test
  void runDelegates() {
    SaasAnalyticsService analytics = mock(SaasAnalyticsService.class);
    new SaasAnalyticsScheduler(analytics).run();
    verify(analytics).computeMonthlyBatch();
  }
}
