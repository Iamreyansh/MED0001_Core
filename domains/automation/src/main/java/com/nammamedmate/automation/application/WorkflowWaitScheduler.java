package com.nammamedmate.automation.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** AC-002: wakes WAIT steps when wait_until (UTC) has elapsed. */
@Component
@ConditionalOnProperty(
    name = "medmate.automation.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class WorkflowWaitScheduler {

  private final WorkflowEngineService engine;

  public WorkflowWaitScheduler(WorkflowEngineService engine) {
    this.engine = engine;
  }

  @Scheduled(fixedDelayString = "${medmate.automation.wait-poll-delay-ms:30000}")
  public void advanceDueWaits() {
    engine.processDueWaits(100);
  }
}
