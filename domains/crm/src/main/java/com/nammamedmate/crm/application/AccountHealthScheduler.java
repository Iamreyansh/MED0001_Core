package com.nammamedmate.crm.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(
    name = "medmate.crm.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class AccountHealthScheduler {

  private final AccountHealthService health;

  public AccountHealthScheduler(AccountHealthService health) {
    this.health = health;
  }

  /** Nightly health recompute at 03:00 Asia/Kolkata. */
  @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Kolkata")
  @Transactional
  public void run() {
    health.recomputeAll();
  }
}
