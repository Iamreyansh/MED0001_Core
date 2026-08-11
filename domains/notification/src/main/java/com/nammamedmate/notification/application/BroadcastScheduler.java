package com.nammamedmate.notification.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ponytail: in-process @Scheduled poller for V1 broadcasts (ceiling: single-instance poll). Upgrade
 * path: SQS/EventBridge fan-out when multi-instance workers need coordination.
 */
@Component
@ConditionalOnProperty(
    name = "medmate.notification.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class BroadcastScheduler {

  private final BroadcastService broadcasts;

  public BroadcastScheduler(BroadcastService broadcasts) {
    this.broadcasts = broadcasts;
  }

  @Scheduled(fixedDelayString = "${medmate.notification.broadcast.poll-ms:15000}")
  public void processQueued() {
    broadcasts.processDue(50);
  }
}
