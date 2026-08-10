package com.nammamedmate.support.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "medmate.support.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class DisputeSlaScheduler {

  private final DisputeService disputes;

  public DisputeSlaScheduler(DisputeService disputes) {
    this.disputes = disputes;
  }

  @Scheduled(fixedDelayString = "${medmate.support.dispute-sla.poll-ms:60000}")
  public void escalateBreaches() {
    disputes.processSlaBreaches(100);
  }
}
