package com.nammamedmate.order.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** ACTIVE carts idle 24h → ABANDONED. */
@Component
@ConditionalOnProperty(
    name = "medmate.order.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class CartAbandonScheduler {

  private final CartService cartService;

  public CartAbandonScheduler(CartService cartService) {
    this.cartService = cartService;
  }

  @Scheduled(cron = "0 15 * * * *", zone = "Asia/Kolkata")
  public void abandonStale() {
    cartService.abandonStaleCarts();
  }
}
