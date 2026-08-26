package com.nammamedmate.pharmacy.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "medmate.kyc.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class KycExpiryAlertScheduler {

  private final KycExpiryAlertDispatchService dispatch;

  public KycExpiryAlertScheduler(KycExpiryAlertDispatchService dispatch) {
    this.dispatch = dispatch;
  }

  @Scheduled(fixedDelayString = "${medmate.kyc.expiry-alert.delay-ms:300000}")
  public void run() {
    dispatch.dispatchDue();
  }
}
