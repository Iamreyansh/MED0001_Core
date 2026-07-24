package com.nammamedmate.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SqsNoOpHandler {

  private static final Logger log = LoggerFactory.getLogger(SqsNoOpHandler.class);

  public void handle(String messageBody) {
    if (messageBody == null || messageBody.isBlank()) {
      log.warn("Received empty SQS payload — ack without requeue");
      return;
    }
    log.info("Worker received message length={}", messageBody.length());
  }
}
