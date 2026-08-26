package com.nammamedmate.notification.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.notification.application.port.out.FcmClientPort;
import com.nammamedmate.notification.application.port.out.MetaWhatsAppClientPort;
import com.nammamedmate.notification.application.port.out.Msg91ClientPort;
import com.nammamedmate.notification.application.port.out.SendGridClientPort;
import com.nammamedmate.notification.application.port.out.SesClientPort;
import com.nammamedmate.notification.application.port.out.TwilioClientPort;
import com.nammamedmate.notification.domain.PushPriority;
import com.nammamedmate.notification.domain.SmsCategory;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HttpVendorClientsTest {

  @Test
  void requireSecretsAndMapHttpCodes() {
    assertThatThrownBy(() -> new HttpVendorClients.HttpMsg91Client(" "))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new HttpVendorClients.HttpMsg91Client(null))
        .isInstanceOf(IllegalStateException.class);
    HttpVendorClients.Poster ok = (u, a, b) -> 200;
    HttpVendorClients.Poster fail = (u, a, b) -> 500;

    var msg91 = new HttpVendorClients.HttpMsg91Client("k", ok);
    assertThat(msg91.isOnDnd("+91")).isFalse();
    assertThat(
            msg91.send(
                new Msg91ClientPort.SendRequest(
                    "+9198", "tpl", "NMMATE", "hi", Map.of(), SmsCategory.OTP)))
        .extracting(Msg91ClientPort.SendResult::success)
        .isEqualTo(true);
    assertThat(
            new HttpVendorClients.HttpMsg91Client("k", fail)
                .send(
                    new Msg91ClientPort.SendRequest(
                        "+9198", "tpl", "NMMATE", "hi", Map.of(), SmsCategory.OTP)))
        .extracting(Msg91ClientPort.SendResult::success)
        .isEqualTo(false);

    assertThat(
            new HttpVendorClients.HttpTwilioClient("sid", "tok", ok)
                .send(new TwilioClientPort.SendRequest("+91", "NMMATE", "b", Map.of()))
                .success())
        .isTrue();
    assertThat(
            new HttpVendorClients.HttpTwilioClient("sid", "tok", fail)
                .send(new TwilioClientPort.SendRequest("+91", "NMMATE", "b", Map.of()))
                .success())
        .isFalse();

    FcmClientPort.PushRequest push =
        new FcmClientPort.PushRequest(
            "t", "T", "B", Map.of(), null, null, PushPriority.HIGH, false);
    assertThat(new HttpVendorClients.HttpFcmClient("k", ok).send(push).success()).isTrue();
    assertThat(new HttpVendorClients.HttpFcmClient("k", fail).send(push).success()).isFalse();

    SesClientPort.SendRequest ses =
        new SesClientPort.SendRequest("a@b.com", "n", "s", "h", "t", List.of(), Map.of());
    assertThat(new HttpVendorClients.HttpSesClient("ap-south-1", ok).send(ses).success()).isTrue();
    assertThat(new HttpVendorClients.HttpSesClient("ap-south-1", fail).send(ses).success())
        .isFalse();

    SendGridClientPort.SendRequest sg =
        new SendGridClientPort.SendRequest("a@b.com", "n", "s", "h", "t", List.of(), Map.of());
    assertThat(new HttpVendorClients.HttpSendGridClient("k", ok).send(sg).success()).isTrue();
    assertThat(new HttpVendorClients.HttpSendGridClient("k", fail).send(sg).success()).isFalse();

    var wa = new HttpVendorClients.HttpMetaWhatsAppClient("tok", "sec", ok);
    assertThat(
            wa.sendTemplate(new MetaWhatsAppClientPort.SendRequest("+91", "tpl", "en", List.of()))
                .success())
        .isTrue();
    assertThat(
            wa.submitTemplate(
                    new MetaWhatsAppClientPort.SubmitTemplateRequest(
                        "n", "UTILITY", "en", "b", Map.of(), "f", List.of()))
                .success())
        .isTrue();
    assertThat(wa.verifyWebhookSignature(null, new byte[0])).isFalse();
    assertThat(wa.verifyWebhookSignature("  ", new byte[0])).isFalse();
    assertThat(wa.verifyWebhookSignature("sha256=dead", new byte[] {1})).isFalse();
    try {
      javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
      mac.init(
          new javax.crypto.spec.SecretKeySpec(
              "sec".getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
      String expected = "sha256=" + java.util.HexFormat.of().formatHex(mac.doFinal(new byte[] {1}));
      assertThat(wa.verifyWebhookSignature(expected, new byte[] {1})).isTrue();
    } catch (Exception e) {
      throw new AssertionError(e);
    }
    assertThat(
            new HttpVendorClients.HttpMetaWhatsAppClient("tok", "sec", fail)
                .sendTemplate(new MetaWhatsAppClientPort.SendRequest("+91", "tpl", "en", List.of()))
                .success())
        .isFalse();
    assertThat(
            new HttpVendorClients.HttpMetaWhatsAppClient("tok", "sec", fail)
                .submitTemplate(
                    new MetaWhatsAppClientPort.SubmitTemplateRequest(
                        "n", "UTILITY", "en", "b", Map.of(), "f", List.of()))
                .success())
        .isFalse();

    assertThat(HttpVendorClients.jdkPoster().post("http://127.0.0.1:1", "a", "{}")).isEqualTo(599);
    try {
      com.sun.net.httpserver.HttpServer server =
          com.sun.net.httpserver.HttpServer.create(
              new java.net.InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/",
          ex -> {
            ex.sendResponseHeaders(204, -1);
            ex.close();
          });
      server.start();
      try {
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        assertThat(HttpVendorClients.jdkPoster().post(url, null, null)).isEqualTo(204);
        assertThat(HttpVendorClients.jdkPoster().post(url, "  ", "{}")).isEqualTo(204);
      } finally {
        server.stop(0);
      }
    } catch (java.io.IOException e) {
      throw new AssertionError(e);
    }
    assertThat(new HttpVendorClients.HttpMsg91Client("k")).isNotNull();
    assertThat(new HttpVendorClients.HttpTwilioClient("s", "t")).isNotNull();
    assertThat(new HttpVendorClients.HttpFcmClient("k")).isNotNull();
    assertThat(new HttpVendorClients.HttpSesClient("ap-south-1")).isNotNull();
    assertThat(new HttpVendorClients.HttpSendGridClient("k")).isNotNull();
    assertThat(new HttpVendorClients.HttpMetaWhatsAppClient("t", "s")).isNotNull();
  }
}
