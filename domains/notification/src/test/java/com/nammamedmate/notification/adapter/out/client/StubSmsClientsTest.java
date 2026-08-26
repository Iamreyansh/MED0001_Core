package com.nammamedmate.notification.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.nammamedmate.notification.application.port.out.AttachmentFetcherPort;
import com.nammamedmate.notification.application.port.out.TwilioClientPort;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StubSmsClientsTest {

  @Test
  void twilioFailResetAndAttachmentFetcher() {
    StubTwilioClient twilio = new StubTwilioClient();
    TwilioClientPort.SendRequest tReq =
        new TwilioClientPort.SendRequest("+919876543210", "NMMATE", "body", Map.of());
    assertThat(twilio.send(tReq).success()).isTrue();
    twilio.setFail(true);
    assertThat(twilio.send(tReq).success()).isFalse();
    twilio.reset();
    assertThat(twilio.sendCallCount()).isZero();
    assertThat(twilio.send(new TwilioClientPort.SendRequest("+1", "NMMATE", "b", null)).success())
        .isTrue();

    assertThat(TwilioClientPort.SendResult.ok("t").messageId()).isEqualTo("t");
    assertThat(TwilioClientPort.SendResult.fail("e").errorMessage()).isEqualTo("e");

    StubAttachmentFetcher fetcher = new StubAttachmentFetcher();
    assertThat(fetcher.fetch(null).found()).isTrue();
    assertThat(fetcher.fetch("https://x").contentType()).isEqualTo("application/pdf");
    fetcher.putOk("https://ok", "hi".getBytes(StandardCharsets.UTF_8), "text/plain");
    assertThat(fetcher.fetch("https://ok").found()).isTrue();
    assertThat(new String(fetcher.fetch("https://ok").content(), StandardCharsets.UTF_8))
        .isEqualTo("hi");
    fetcher.putNotFound("https://404");
    assertThat(fetcher.fetch("https://404").found()).isFalse();
    fetcher.setDefaultNotFound(true);
    assertThat(fetcher.fetch("https://other").found()).isFalse();
    fetcher.reset();
    assertThat(fetcher.fetch("https://other").found()).isTrue();

    assertThat(AttachmentFetcherPort.FetchResult.ok(null, "t").content()).isEmpty();
    assertThat(AttachmentFetcherPort.FetchResult.notFound("e").errorMessage()).isEqualTo("e");
  }
}
