package com.nammamedmate.order.application;

import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderLifecycleSchedulerTest {

  @Mock private OrderLifecycleService lifecycle;

  @Test
  void runLifecycleJobsDelegates() {
    OrderLifecycleScheduler scheduler = new OrderLifecycleScheduler(lifecycle);
    scheduler.runLifecycleJobs();
    verify(lifecycle).cancelTimedOutAcceptances();
    verify(lifecycle).escalateMissingRiders();
    verify(lifecycle).markSlaBreaches();
  }
}
