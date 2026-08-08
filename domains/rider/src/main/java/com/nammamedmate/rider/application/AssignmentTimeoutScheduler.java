package com.nammamedmate.rider.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** BR-003 / AC-002: 5-minute accept timeout → TIMED_OUT + requeue. */
@Component
@ConditionalOnProperty(
    name = "medmate.rider.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class AssignmentTimeoutScheduler {

  private final DispatchService dispatch;

  public AssignmentTimeoutScheduler(DispatchService dispatch) {
    this.dispatch = dispatch;
  }

  @Scheduled(fixedDelayString = "${medmate.rider.assignment-timeout-delay-ms:30000}")
  @Transactional
  public void timeoutExpired() {
    dispatch.timeoutExpiredAssignments();
  }
}
