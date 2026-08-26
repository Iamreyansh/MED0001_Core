package com.nammamedmate.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.nammamedmate.messaging.OutboxMessage;
import com.nammamedmate.messaging.SqsOutboxTransport;
import com.nammamedmate.testing.Containers;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

@Testcontainers(disabledWithoutDocker = true)
class OutboxSqsLocalStackIT {

  /** SQS-only; no docker.sock mount (Colima/macOS rejects LocalStackContainer's lsetxattr). */
  @Container
  static final GenericContainer<?> LOCALSTACK =
      new GenericContainer<>(DockerImageName.parse(Containers.LOCALSTACK_IMAGE))
          .withExposedPorts(4566)
          .withEnv("SERVICES", "sqs")
          .waitingFor(
              Wait.forHttp("/_localstack/health")
                  .forPort(4566)
                  .forStatusCode(200)
                  .withStartupTimeout(Duration.ofMinutes(2)))
          .withStartupAttempts(3);

  @Test
  void outboxTransportPublishesAndFailedConsumeLeavesMessage() {
    URI endpoint =
        URI.create("http://" + LOCALSTACK.getHost() + ":" + LOCALSTACK.getMappedPort(4566));
    try (SqsClient sqs =
        SqsClient.builder()
            .endpointOverride(endpoint)
            .region(Region.US_EAST_1)
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
            .build()) {
      String queueUrl =
          sqs.createQueue(CreateQueueRequest.builder().queueName("domain-events").build())
              .queueUrl();
      new SqsOutboxTransport(sqs, queueUrl)
          .accept(
              new OutboxMessage(
                  UUID.randomUUID(),
                  "customer.notification.requested",
                  "{\"type\":\"customer.notification.requested\",\"eventId\":\"e1\"}",
                  Instant.now(),
                  false));

      List<Message> first =
          sqs.receiveMessage(
                  ReceiveMessageRequest.builder()
                      .queueUrl(queueUrl)
                      .maxNumberOfMessages(1)
                      .waitTimeSeconds(5)
                      .visibilityTimeout(1)
                      .build())
              .messages();
      assertThat(first).hasSize(1);
      assertThat(first.getFirst().body()).contains("customer.notification.requested");

      // Simulate worker failure: do not delete. After visibility timeout the message returns.
      try {
        Thread.sleep(1500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      List<Message> retry =
          sqs.receiveMessage(
                  ReceiveMessageRequest.builder()
                      .queueUrl(queueUrl)
                      .maxNumberOfMessages(1)
                      .waitTimeSeconds(5)
                      .build())
              .messages();
      assertThat(retry).hasSize(1);
    }
  }
}
