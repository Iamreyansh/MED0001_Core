package com.nammamedmate.order.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Pharmacy 15m response expiry + broadcast 30m auto-expiry (customer push via outbox). */
@Component
@ConditionalOnProperty(
    name = "medmate.order.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class RxQuoteExpiryScheduler {

  private final RxQuoteBroadcastService service;

  public RxQuoteExpiryScheduler(RxQuoteBroadcastService service) {
    this.service = service;
  }

  @Scheduled(cron = "0 */1 * * * *", zone = "Asia/Kolkata")
  public void expireWindows() {
    service.expirePharmacyResponseWindows();
    service.expireBroadcastsAndNotify();
  }
}
