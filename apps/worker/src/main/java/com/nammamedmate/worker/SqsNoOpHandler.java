package com.nammamedmate.worker;

import com.nammamedmate.notification.adapter.in.messaging.CustomerNotificationRequestedHandler;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * SQS entry point. When the notification handler bean is present (JDBC-backed worker profiles),
 * routes {@code customer.notification.requested} payloads to in-app inbox creation.
 */
@Component
public class SqsNoOpHandler {

  private static final Logger log = LoggerFactory.getLogger(SqsNoOpHandler.class);

  private final ObjectProvider<CustomerNotificationRequestedHandler> notificationHandler;

  public SqsNoOpHandler(ObjectProvider<CustomerNotificationRequestedHandler> notificationHandler) {
    this.notificationHandler = notificationHandler;
  }

  public void handle(String messageBody) {
    if (messageBody == null || messageBody.isBlank()) {
      log.warn("Received empty SQS payload — ack without requeue");
      return;
    }
    Optional.ofNullable(notificationHandler.getIfAvailable())
        .ifPresentOrElse(
            h -> h.handleMessage(messageBody),
            () -> log.info("Worker received message length={}", messageBody.length()));
  }
}
