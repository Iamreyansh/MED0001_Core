package com.nammamedmate.notification.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NotificationDispatchConsumerTest {

  @Test
  void tryHandleDispatchesWhenPayloadLooksLikeNotification() {
    CustomerNotificationRequestedHandler handler = mock(CustomerNotificationRequestedHandler.class);
    NotificationDispatchConsumer consumer =
        new NotificationDispatchConsumer(handler, new ObjectMapper());
    String json = "{\"type\":\"misc.ping\",\"channel\":\"PUSH\",\"title\":\"t\",\"body\":\"b\"}";
    assertThat(consumer.tryHandle(json)).isTrue();
    verify(handler).handleMessage(json);
    consumer.handleMessage(json);
    verify(handler, org.mockito.Mockito.times(2)).handleMessage(json);
  }

  @Test
  void tryHandleAcksUnknownWithoutNotificationShape() {
    CustomerNotificationRequestedHandler handler = mock(CustomerNotificationRequestedHandler.class);
    NotificationDispatchConsumer consumer =
        new NotificationDispatchConsumer(handler, new ObjectMapper());
    assertThat(consumer.tryHandle(null)).isFalse();
    assertThat(consumer.tryHandle("  ")).isFalse();
    assertThat(consumer.tryHandle("{\"type\":\"automation.action.executed\"}")).isFalse();
    verifyNoInteractions(handler);
    assertThatThrownBy(() -> consumer.tryHandle("{bad}")).isInstanceOf(IllegalStateException.class);
    assertThat(NotificationDispatchConsumer.looksDispatchable(null)).isFalse();
    assertThat(NotificationDispatchConsumer.looksDispatchable(Map.of())).isFalse();
    assertThat(NotificationDispatchConsumer.looksDispatchable(Map.of("title", "x"))).isTrue();
    assertThat(NotificationDispatchConsumer.looksDispatchable(Map.of("phone", "+91"))).isTrue();
    assertThat(NotificationDispatchConsumer.looksDispatchable(Map.of("email", "a@b.com"))).isTrue();
    assertThat(NotificationDispatchConsumer.looksDispatchable(Map.of("title", "  "))).isFalse();
    assertThat(consumer.tryHandle("{\"title\":\"Hi\",\"body\":\"There\"}")).isTrue();
    assertThat(consumer.tryHandle("{\"type\":\"misc.ping\",\"payload\":{\"title\":\"t\"}}"))
        .isTrue();
    assertThat(NotificationDispatchConsumer.looksDispatchable(Map.of("channels", List.of("SMS"))))
        .isTrue();
    assertThat(NotificationDispatchConsumer.looksDispatchable(Map.of("body", "x"))).isTrue();
    assertThat(NotificationDispatchConsumer.looksDispatchable(Map.of("to_phone", "+91"))).isTrue();
    assertThat(NotificationDispatchConsumer.looksDispatchable(Map.of("to_email", "a@b.com")))
        .isTrue();
  }
}
