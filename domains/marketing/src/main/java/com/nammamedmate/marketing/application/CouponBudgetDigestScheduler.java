package com.nammamedmate.marketing.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Daily coupon budget-burn digest at 09:00 Asia/Kolkata. */
@Component
@ConditionalOnProperty(
    name = "medmate.marketing.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class CouponBudgetDigestScheduler {

  private final CouponService coupons;

  public CouponBudgetDigestScheduler(CouponService coupons) {
    this.coupons = coupons;
  }

  @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Kolkata")
  public void sendDigest() {
    coupons.sendDailyBudgetBurnDigest();
  }
}
