package com.nammamedmate.automation.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** AC-007: expire PENDING approvals after the 4h window and fire alternatives. */
@Component
@ConditionalOnProperty(
    name = "medmate.automation.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ApprovalExpiryScheduler {

  private final ApprovalQueueService queue;

  public ApprovalExpiryScheduler(ApprovalQueueService queue) {
    this.queue = queue;
  }

  @Scheduled(fixedDelayString = "${medmate.automation.approval-expiry-delay-ms:30000}")
  public void expireDue() {
    queue.expireDue(100);
  }
}
