package com.nammamedmate.worker;

import org.springframework.stereotype.Component;

/** SQS entry point — routes domain events. Failures propagate so the poller does not ack. */
@Component
public class SqsNoOpHandler {

  private final DomainEventRouter router;

  public SqsNoOpHandler(DomainEventRouter router) {
    this.router = router;
  }

  public void handle(String messageBody) {
    router.handle(messageBody);
  }
}
