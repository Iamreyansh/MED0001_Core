package com.nammamedmate.medicine_schedule.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Daily refill alert push while medicines remain in alert state. */
@Component
@ConditionalOnProperty(
    name = "medmate.medicine-schedule.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class RefillAlertPushScheduler {

  private static final Logger log = LoggerFactory.getLogger(RefillAlertPushScheduler.class);

  private final RefillAlertService refills;

  public RefillAlertPushScheduler(RefillAlertService refills) {
    this.refills = refills;
  }

  @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Kolkata")
  public void pushRefillAlerts() {
    int n = refills.dispatchDailyRefillAlerts();
    if (n > 0) {
      log.info("Dispatched {} refill alert notifications", n);
    }
  }
}
