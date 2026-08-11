package com.nammamedmate.notification.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.nammamedmate.notification.application.port.out.Msg91ClientPort;
import com.nammamedmate.notification.application.port.out.TwilioClientPort;
import com.nammamedmate.notification.domain.SmsCategory;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StubSmsClientsTest {

  @Test
  void msg91TimeoutFailDndAndTwilio() {
    StubMsg91Client msg91 = new StubMsg91Client();
    Msg91ClientPort.SendRequest req =
        new Msg91ClientPort.SendRequest(
            "+919876543210", "dlt", "NMMATE", "body", Map.of("1", "x"), SmsCategory.OTP);
    assertThat(msg91.send(req).success()).isTrue();
    msg91.setTimeout(true);
    assertThat(msg91.send(req).timedOut()).isTrue();
    msg91.setTimeout(false);
    msg91.setFail(true);
    assertThat(msg91.send(req).success()).isFalse();
    msg91.markDnd("+919876543210");
    assertThat(msg91.isOnDnd("+919876543210")).isTrue();
    assertThat(msg91.isOnDnd(null)).isFalse();
    msg91.clearDnd();
    assertThat(msg91.isOnDnd("+919876543210")).isFalse();
    msg91.reset();
    assertThat(msg91.sendCallCount()).isZero();

    StubTwilioClient twilio = new StubTwilioClient();
    TwilioClientPort.SendRequest tReq =
        new TwilioClientPort.SendRequest("+919876543210", "NMMATE", "body", Map.of());
    assertThat(twilio.send(tReq).success()).isTrue();
    twilio.setFail(true);
    assertThat(twilio.send(tReq).success()).isFalse();
    twilio.reset();
    assertThat(twilio.sendCallCount()).isZero();

    assertThat(Msg91ClientPort.SendResult.ok("m").messageId()).isEqualTo("m");
    assertThat(Msg91ClientPort.SendResult.timeout().timedOut()).isTrue();
    assertThat(Msg91ClientPort.SendResult.fail("e").errorMessage()).isEqualTo("e");
    assertThat(TwilioClientPort.SendResult.ok("t").messageId()).isEqualTo("t");
    assertThat(TwilioClientPort.SendResult.fail("e").errorMessage()).isEqualTo("e");
  }
}
