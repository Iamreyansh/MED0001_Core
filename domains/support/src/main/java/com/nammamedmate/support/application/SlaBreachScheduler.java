package com.nammamedmate.support.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "medmate.support.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class SlaBreachScheduler {

  private final SlaService sla;

  public SlaBreachScheduler(SlaService sla) {
    this.sla = sla;
  }

  @Scheduled(fixedDelayString = "${medmate.support.sla.poll-ms:60000}")
  public void escalateBreaches() {
    sla.processSlaBreaches(100);
  }
}
