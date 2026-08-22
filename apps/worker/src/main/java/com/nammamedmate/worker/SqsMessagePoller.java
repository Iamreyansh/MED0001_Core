package com.nammamedmate.worker;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

/**
 * Long-poll SQS consumer for Fargate. No-ops when {@code medmate.sqs.queue-url} is blank (local).
 */
@Component
public class SqsMessagePoller implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(SqsMessagePoller.class);

  private final SqsClient sqsClient;
  private final SqsNoOpHandler handler;
  private final String queueUrl;

  private volatile boolean running;
  private Thread worker;

  SqsMessagePoller(
      SqsClient sqsClient,
      SqsNoOpHandler handler,
      @Value("${medmate.sqs.queue-url:}") String queueUrl) {
    this.sqsClient = sqsClient;
    this.handler = handler;
    this.queueUrl = queueUrl == null ? "" : queueUrl.trim();
  }

  @Override
  public void start() {
    if (queueUrl.isEmpty()) {
      log.info("SQS queue URL blank — poller idle");
      return;
    }
    if (running) {
      return;
    }
    running = true;
    worker = new Thread(this::loop, "sqs-poller");
    worker.setDaemon(false);
    worker.start();
    log.info("SQS poller started");
  }

  @Override
  public void stop() {
    running = false;
    Thread t = worker;
    if (t != null) {
      t.interrupt();
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  void loop() {
    while (running) {
      try {
        pollOnce();
      } catch (RuntimeException e) {
        log.warn("SQS poll failed: {}", e.toString());
        sleepQuietly(50);
      }
    }
  }

  void pollOnce() {
    ReceiveMessageRequest request =
        ReceiveMessageRequest.builder()
            .queueUrl(queueUrl)
            .maxNumberOfMessages(10)
            .waitTimeSeconds(20)
            .build();
    List<Message> messages = sqsClient.receiveMessage(request).messages();
    for (Message message : messages) {
      process(message);
    }
  }

  void process(Message message) {
    sqsClient.changeMessageVisibility(
        ChangeMessageVisibilityRequest.builder()
            .queueUrl(queueUrl)
            .receiptHandle(message.receiptHandle())
            .visibilityTimeout(120)
            .build());
    handler.handle(message.body());
    // ack only after a successful handle — failures stay visible for retry/DLQ
    sqsClient.deleteMessage(
        DeleteMessageRequest.builder()
            .queueUrl(queueUrl)
            .receiptHandle(message.receiptHandle())
            .build());
  }

  /** Package-visible for tests — restores interrupt flag when sleep is interrupted. */
  void sleepQuietly(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
