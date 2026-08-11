package com.nammamedmate.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.notification.adapter.in.messaging.CustomerNotificationRequestedHandler;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

@ExtendWith(MockitoExtension.class)
class SqsMessagePollerTest {

  @Mock private SqsClient sqsClient;

  private static SqsNoOpHandler noOpHandler() {
    @SuppressWarnings("unchecked")
    ObjectProvider<CustomerNotificationRequestedHandler> provider = mock(ObjectProvider.class);
    lenient().when(provider.getIfAvailable()).thenReturn(null);
    return new SqsNoOpHandler(provider);
  }

  @Test
  void blankQueueUrlDoesNotStartThread() {
    SqsMessagePoller poller = new SqsMessagePoller(sqsClient, noOpHandler(), "  ");
    poller.start();
    assertThat(poller.isRunning()).isFalse();
    poller.stop();
  }

  @Test
  void nullQueueUrlTreatedAsBlank() {
    SqsMessagePoller poller = new SqsMessagePoller(sqsClient, noOpHandler(), null);
    poller.start();
    assertThat(poller.isRunning()).isFalse();
  }

  @Test
  void pollOnceProcessesAndDeletes() {
    Message message =
        Message.builder().body("{\"type\":\"order.created\"}").receiptHandle("rh-1").build();
    when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
        .thenReturn(ReceiveMessageResponse.builder().messages(List.of(message)).build());

    SqsMessagePoller poller =
        new SqsMessagePoller(sqsClient, noOpHandler(), "https://sqs.example/q");
    poller.pollOnce();

    verify(sqsClient).deleteMessage(any(DeleteMessageRequest.class));
  }

  @Test
  void processDeletesAfterHandle() {
    SqsMessagePoller poller =
        new SqsMessagePoller(sqsClient, noOpHandler(), "https://sqs.example/q");
    Message message = Message.builder().body("x").receiptHandle("rh").build();
    poller.process(message);
    verify(sqsClient).deleteMessage(any(DeleteMessageRequest.class));
  }

  @Test
  void startStopLifecycle() {
    when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
        .thenReturn(ReceiveMessageResponse.builder().messages(List.of()).build());
    SqsMessagePoller poller =
        new SqsMessagePoller(sqsClient, noOpHandler(), "https://sqs.example/q");
    poller.start();
    assertThat(poller.isRunning()).isTrue();
    poller.start();
    verify(sqsClient, timeout(1000).atLeastOnce()).receiveMessage(any(ReceiveMessageRequest.class));
    poller.stop();
    assertThat(poller.isRunning()).isFalse();
    poller.stop();
  }

  @Test
  void loopHandlesFailureThenStops() throws Exception {
    when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
        .thenThrow(new RuntimeException("boom"));
    SqsMessagePoller poller =
        new SqsMessagePoller(sqsClient, noOpHandler(), "https://sqs.example/q");
    poller.start();
    Thread.sleep(120);
    poller.stop();
    verify(sqsClient, atLeastOnce()).receiveMessage(any(ReceiveMessageRequest.class));
  }

  @Test
  void sleepQuietlyRestoresInterruptFlag() throws Exception {
    SqsMessagePoller poller =
        new SqsMessagePoller(sqsClient, noOpHandler(), "https://sqs.example/q");
    AtomicBoolean interrupted = new AtomicBoolean();
    Thread t =
        new Thread(
            () -> {
              Thread.currentThread().interrupt();
              poller.sleepQuietly(1000);
              interrupted.set(Thread.currentThread().isInterrupted());
            });
    t.start();
    t.join(2000);
    assertThat(interrupted.get()).isTrue();
  }
}
