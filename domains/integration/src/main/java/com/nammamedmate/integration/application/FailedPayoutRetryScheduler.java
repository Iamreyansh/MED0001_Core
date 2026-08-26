package com.nammamedmate.integration.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** AC-006: auto-retry failed CashfreePayout payouts once after 1 hour. */
@Component
@ConditionalOnProperty(
    name = "medmate.integration.payout-retry.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class FailedPayoutRetryScheduler {

  private final CashfreeIntegrationService service;

  public FailedPayoutRetryScheduler(CashfreeIntegrationService service) {
    this.service = service;
  }

  @Scheduled(fixedDelayString = "${medmate.integration.payout-retry-delay-ms:300000}")
  public void retryFailedPayouts() {
    service.retryFailedPayouts();
  }
}
