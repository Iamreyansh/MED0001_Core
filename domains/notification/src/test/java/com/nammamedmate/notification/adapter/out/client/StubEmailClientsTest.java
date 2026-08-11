package com.nammamedmate.notification.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.nammamedmate.notification.application.port.out.AttachmentFetcherPort;
import com.nammamedmate.notification.application.port.out.SendGridClientPort;
import com.nammamedmate.notification.application.port.out.SesClientPort;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StubEmailClientsTest {

  @Test
  void sendRequestAndAttachmentNullCopies() {
    assertThat(
            new SendGridClientPort.SendRequest("a@b.com", null, "s", "h", "t", null, null)
                .attachments())
        .isEmpty();
    assertThat(
            new SesClientPort.SendRequest("a@b.com", null, "s", "h", "t", null, null).attachments())
        .isEmpty();
    assertThat(new SesClientPort.Attachment("f", null, "t").content()).isEmpty();
    assertThat(new SendGridClientPort.Attachment("f", null, "t").content()).isEmpty();
  }

  @Test
  void sendGridModes() {
    StubSendGridClient client = new StubSendGridClient();
    assertThat(client.send(req()).success()).isTrue();
    client.setServerError(true);
    assertThat(client.send(req()).serverError()).isTrue();
    client.setServerError(false);
    client.setFail(true);
    assertThat(client.send(req()).success()).isFalse();
    assertThat(client.sendCallCount()).isEqualTo(3);
    client.reset();
    assertThat(client.sendCallCount()).isZero();
  }

  @Test
  void sesAndAttachmentFetcher() {
    StubSesClient ses = new StubSesClient();
    assertThat(
            ses.send(
                    new SesClientPort.SendRequest(
                        "a@b.com", "A", "s", "h", "t", List.of(), Map.of()))
                .success())
        .isTrue();
    ses.setFail(true);
    assertThat(
            ses.send(new SesClientPort.SendRequest("a@b.com", null, "s", "h", "t", null, null))
                .success())
        .isFalse();
    ses.reset();
    assertThat(ses.sendCallCount()).isZero();

    StubAttachmentFetcher fetcher = new StubAttachmentFetcher();
    assertThat(fetcher.fetch("https://ok").found()).isTrue();
    fetcher.putNotFound("https://404");
    assertThat(fetcher.fetch("https://404").found()).isFalse();
    fetcher.setDefaultNotFound(true);
    assertThat(fetcher.fetch("https://other").found()).isFalse();
    fetcher.reset();
    fetcher.putOk("https://x", new byte[] {1}, "application/pdf");
    AttachmentFetcherPort.FetchResult ok = fetcher.fetch("https://x");
    assertThat(ok.found()).isTrue();
    assertThat(ok.content()).containsExactly((byte) 1);

    assertThat(new SendGridClientPort.Attachment("f", null, "t").sizeBytes()).isZero();
    assertThat(SendGridClientPort.SendResult.fail("e").success()).isFalse();
    assertThat(SesClientPort.SendResult.fail("e").success()).isFalse();
    assertThat(AttachmentFetcherPort.FetchResult.ok(null, "t").content()).isEmpty();
    assertThat(AttachmentFetcherPort.FetchResult.ok(new byte[] {1}, "t").content())
        .containsExactly((byte) 1);
  }

  private static SendGridClientPort.SendRequest req() {
    return new SendGridClientPort.SendRequest(
        "a@b.com", "A", "s", "<p>h</p>", "t", List.of(), Map.of());
  }
}
