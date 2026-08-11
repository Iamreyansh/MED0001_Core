package com.nammamedmate.notification.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.notification.application.port.out.MetaWhatsAppClientPort;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StubMetaWhatsAppClientTest {

  @Test
  void sendSubmitSignAndVerify() {
    StubMetaWhatsAppClient client = new StubMetaWhatsAppClient();
    MetaWhatsAppClientPort.SendResult ok =
        client.sendTemplate(
            new MetaWhatsAppClientPort.SendRequest(
                "+919876543210", "ORDER_CONFIRMED", "en", List.of()));
    assertThat(ok.success()).isTrue();
    assertThat(ok.waMessageId()).startsWith("wamid.");

    client.setFailSend(true);
    assertThat(
            client
                .sendTemplate(new MetaWhatsAppClientPort.SendRequest("+1", "T", "en", List.of()))
                .success())
        .isFalse();

    MetaWhatsAppClientPort.SubmitTemplateResult submitted =
        client.submitTemplate(
            new MetaWhatsAppClientPort.SubmitTemplateRequest(
                "REORDER_REMINDER", "UTILITY", "en", "body", null, null, List.of()));
    assertThat(submitted.success()).isTrue();
    assertThat(submitted.metaTemplateId()).contains("reorder_reminder");

    client.setFailSubmit(true);
    assertThat(
            client
                .submitTemplate(
                    new MetaWhatsAppClientPort.SubmitTemplateRequest(
                        "X", "UTILITY", "en", "b", Map.of(), "f", List.of()))
                .success())
        .isFalse();

    byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
    String sig = client.sign(body);
    assertThat(client.verifyWebhookSignature(sig, body)).isTrue();
    assertThat(client.verifyWebhookSignature("sha256=bad", body)).isFalse();
    assertThat(client.verifyWebhookSignature(null, body)).isFalse();
    assertThat(client.verifyWebhookSignature(client.sign(new byte[0]), null)).isTrue();
    assertThat(client.verifyWebhookSignature(client.sign(new byte[0]), new byte[0])).isTrue();

    assertThat(client.sendCallCount()).isEqualTo(2);
    assertThat(client.submitCallCount()).isEqualTo(2);
    client.reset();
    assertThat(client.sendCallCount()).isZero();

    StubMetaWhatsAppClient blankSecret = new StubMetaWhatsAppClient("  ");
    assertThat(blankSecret.verifyWebhookSignature(blankSecret.sign(body), body)).isTrue();
    StubMetaWhatsAppClient nullSecret = new StubMetaWhatsAppClient(null);
    assertThat(nullSecret.verifyWebhookSignature(nullSecret.sign(body), body)).isTrue();
    assertThat(client.verifyWebhookSignature("   ", body)).isFalse();
    assertThat(StubMetaWhatsAppClient.hmacHex("s", null)).hasSize(64);
  }

  @Test
  void hmacFailurePath() {
    assertThat(StubMetaWhatsAppClient.hmacHex("s", "p".getBytes(StandardCharsets.UTF_8)))
        .hasSize(64);
    assertThatThrownBy(() -> StubMetaWhatsAppClient.hmacHex(null, new byte[0]))
        .isInstanceOf(IllegalStateException.class);
  }
}
