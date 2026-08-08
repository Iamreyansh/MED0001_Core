package com.nammamedmate.rider.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** AC-003: Monday morning IST weekly payout computation. */
@Component
@ConditionalOnProperty(
    name = "medmate.rider.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class WeeklyPayoutScheduler {

  private final RiderPayoutService payouts;

  public WeeklyPayoutScheduler(RiderPayoutService payouts) {
    this.payouts = payouts;
  }

  @Scheduled(cron = "0 5 0 * * MON", zone = "Asia/Kolkata")
  public void computePreviousWeek() {
    payouts.computeWeeklyPayouts();
  }

  /** AC-007: poll for Razorpay retry after 24h. */
  @Scheduled(fixedDelayString = "${medmate.rider.payout-retry-delay-ms:300000}")
  public void retryFailedPayouts() {
    payouts.retryDuePayouts();
  }
}
