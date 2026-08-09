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
public class SubscriptionRenewalScheduler {

  private final SubscriptionService subscriptions;

  public SubscriptionRenewalScheduler(SubscriptionService subscriptions) {
    this.subscriptions = subscriptions;
  }

  /** Daily auto-renew / grace / trial / cancel processing in Asia/Kolkata. */
  @Scheduled(cron = "0 10 1 * * *", zone = "Asia/Kolkata")
  @Transactional
  public void run() {
    subscriptions.processScheduledJobs();
  }
}
