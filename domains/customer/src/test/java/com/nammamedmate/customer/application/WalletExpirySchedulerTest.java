package com.nammamedmate.customer.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

class WalletExpirySchedulerTest {

  @Test
  void expireCredits_delegates() {
    WalletService service = mock(WalletService.class);
    new WalletExpiryScheduler(service).expireCredits();
    verify(service).expireCredits();
  }
}
