package com.nammamedmate.messaging;

import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/** Publishes an outbox row to SQS. Does not mark the row published — the dispatcher does. */
public final class SqsOutboxTransport implements Consumer<OutboxMessage> {

  private final SqsClient sqs;
  private final String queueUrl;

  public SqsOutboxTransport(SqsClient sqs, String queueUrl) {
    this.sqs = Objects.requireNonNull(sqs, "sqs");
    this.queueUrl = queueUrl == null ? "" : queueUrl.trim();
  }

  @Override
  public void accept(OutboxMessage message) {
    if (queueUrl.isEmpty()) {
      throw new IllegalStateException("SQS queue URL is blank");
    }
    Objects.requireNonNull(message, "message");
    sqs.sendMessage(
        SendMessageRequest.builder()
            .queueUrl(queueUrl)
            .messageBody(message.payloadJson())
            .messageAttributes(
                Map.of(
                    "eventType",
                    MessageAttributeValue.builder()
                        .dataType("String")
                        .stringValue(message.type())
                        .build()))
            .build());
  }
}
