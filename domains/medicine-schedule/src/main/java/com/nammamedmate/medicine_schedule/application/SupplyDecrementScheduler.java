package com.nammamedmate.medicine_schedule.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 00:30 IST — nightly supply decrement for tracked medicines. */
@Component
@ConditionalOnProperty(
    name = "medmate.medicine-schedule.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class SupplyDecrementScheduler {

  private static final Logger log = LoggerFactory.getLogger(SupplyDecrementScheduler.class);

  private final RefillAlertService refills;

  public SupplyDecrementScheduler(RefillAlertService refills) {
    this.refills = refills;
  }

  @Scheduled(cron = "0 30 0 * * *", zone = "Asia/Kolkata")
  public void decrementSupply() {
    int n = refills.runNightlySupplyDecrement();
    if (n > 0) {
      log.info("Nightly supply decrement applied to {} medicines", n);
    }
  }
}
