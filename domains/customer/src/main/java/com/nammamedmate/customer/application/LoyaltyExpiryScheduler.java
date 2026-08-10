package com.nammamedmate.customer.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Nightly loyalty points FIFO expiry in Asia/Kolkata (EPIC-013 STORY-006). */
@Component
@ConditionalOnProperty(
    name = "medmate.customer.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class LoyaltyExpiryScheduler {

  private final LoyaltyService loyalty;

  public LoyaltyExpiryScheduler(LoyaltyService loyalty) {
    this.loyalty = loyalty;
  }

  @Scheduled(cron = "0 45 2 * * *", zone = "Asia/Kolkata")
  public void expirePoints() {
    loyalty.expirePoints();
  }
}
