package com.nammamedmate.marketing.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every 15 minutes: set is_live=false where valid_until has passed. */
@Component
@ConditionalOnProperty(
    name = "medmate.marketing.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class BannerExpiryScheduler {

  private final BannerService banners;

  public BannerExpiryScheduler(BannerService banners) {
    this.banners = banners;
  }

  @Scheduled(cron = "0 */15 * * * *", zone = "Asia/Kolkata")
  public void deactivateExpired() {
    banners.deactivateExpired();
  }
}
