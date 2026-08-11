package com.nammamedmate.notification.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.nammamedmate.notification.application.port.out.FcmClientPort;
import com.nammamedmate.notification.domain.PushPriority;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StubFcmClientTest {

  @Test
  void successAndNotRegisteredAndClear() {
    StubFcmClient client = new StubFcmClient();
    FcmClientPort.PushRequest req =
        new FcmClientPort.PushRequest(
            "tok", "t", "b", Map.of("a", "1"), null, null, PushPriority.HIGH, false);
    assertThat(client.send(req).success()).isTrue();
    client.markNotRegistered("tok");
    assertThat(client.send(req).errorCode()).isEqualTo("NOT_REGISTERED");
    client.clearNotRegistered();
    assertThat(client.send(req).success()).isTrue();
  }
}
