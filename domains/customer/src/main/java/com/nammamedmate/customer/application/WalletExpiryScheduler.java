package com.nammamedmate.customer.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Nightly wallet credit expiry in Asia/Kolkata (EPIC-002 STORY-003). */
@Component
@ConditionalOnProperty(
    name = "medmate.customer.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class WalletExpiryScheduler {

  private final WalletService wallets;

  public WalletExpiryScheduler(WalletService wallets) {
    this.wallets = wallets;
  }

  @Scheduled(cron = "0 30 2 * * *", zone = "Asia/Kolkata")
  public void expireCredits() {
    wallets.expireCredits();
  }
}
