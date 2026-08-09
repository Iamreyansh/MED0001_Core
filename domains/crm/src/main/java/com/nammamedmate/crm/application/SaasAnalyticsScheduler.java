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
public class SaasAnalyticsScheduler {

  private final SaasAnalyticsService analytics;

  public SaasAnalyticsScheduler(SaasAnalyticsService analytics) {
    this.analytics = analytics;
  }

  /** Monthly SaaS metrics + cohort cache on the 1st at 03:30 Asia/Kolkata. */
  @Scheduled(cron = "0 30 3 1 * *", zone = "Asia/Kolkata")
  @Transactional
  public void run() {
    analytics.computeMonthlyBatch();
  }
}
