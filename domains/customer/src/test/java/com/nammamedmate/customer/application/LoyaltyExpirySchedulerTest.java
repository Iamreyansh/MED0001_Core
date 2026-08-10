package com.nammamedmate.customer.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

class LoyaltyExpirySchedulerTest {

  @Test
  void expirePoints_delegates() {
    LoyaltyService service = mock(LoyaltyService.class);
    new LoyaltyExpiryScheduler(service).expirePoints();
    verify(service).expirePoints();
  }
}
