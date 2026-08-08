package com.nammamedmate.order.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

class RxQuoteExpirySchedulerTest {

  @Test
  void expireWindowsDelegates() {
    RxQuoteBroadcastService service = mock(RxQuoteBroadcastService.class);
    new RxQuoteExpiryScheduler(service).expireWindows();
    verify(service).expirePharmacyResponseWindows();
    verify(service).expireBroadcastsAndNotify();
  }
}
