package com.nammamedmate.order.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Acceptance timeout cancel, no-rider alert, SLA breach flag. */
@Component
@ConditionalOnProperty(
    name = "medmate.order.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class OrderLifecycleScheduler {

  private final OrderLifecycleService lifecycle;

  public OrderLifecycleScheduler(OrderLifecycleService lifecycle) {
    this.lifecycle = lifecycle;
  }

  @Scheduled(cron = "0 */1 * * * *", zone = "Asia/Kolkata")
  public void runLifecycleJobs() {
    lifecycle.cancelTimedOutAcceptances();
    lifecycle.escalateMissingRiders();
    lifecycle.markSlaBreaches();
  }
}
