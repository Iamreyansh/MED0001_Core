package com.nammamedmate.api.config;

import com.nammamedmate.messaging.SqsEventDispatcher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Drains unpublished outbox rows to in-process consumers (local ponytail) or SQS (prod). */
@Component
@ConditionalOnProperty(
    name = "medmate.outbox.dispatch.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class OutboxDispatchScheduler {

  private final SqsEventDispatcher dispatcher;

  public OutboxDispatchScheduler(SqsEventDispatcher dispatcher) {
    this.dispatcher = dispatcher;
  }

  @Scheduled(fixedDelayString = "${medmate.outbox.dispatch.delay-ms:5000}")
  public void dispatchOutbox() {
    dispatcher.dispatchOnce();
  }
}
