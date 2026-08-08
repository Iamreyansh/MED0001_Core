package com.nammamedmate.order.application;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CartAbandonSchedulerTest {

  @Mock private CartService cartService;

  @Test
  void delegates() {
    when(cartService.abandonStaleCarts()).thenReturn(2);
    new CartAbandonScheduler(cartService).abandonStale();
    verify(cartService).abandonStaleCarts();
  }
}
