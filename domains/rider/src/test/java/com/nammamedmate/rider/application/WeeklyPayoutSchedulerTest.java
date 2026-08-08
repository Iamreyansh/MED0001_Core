package com.nammamedmate.rider.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class WeeklyPayoutSchedulerTest {

  @Test
  void schedulesComputeAndRetry() {
    RiderPayoutService payouts = mock(RiderPayoutService.class);
    when(payouts.computeWeeklyPayouts()).thenReturn(2);
    when(payouts.retryDuePayouts()).thenReturn(1);
    WeeklyPayoutScheduler scheduler = new WeeklyPayoutScheduler(payouts);
    scheduler.computePreviousWeek();
    scheduler.retryFailedPayouts();
    verify(payouts).computeWeeklyPayouts();
    verify(payouts).retryDuePayouts();
  }
}
