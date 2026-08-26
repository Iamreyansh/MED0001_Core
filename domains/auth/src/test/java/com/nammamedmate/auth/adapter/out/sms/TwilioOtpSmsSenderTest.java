package com.nammamedmate.auth.adapter.out.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TwilioOtpSmsSenderTest {

  @Test
  void requiresSecretsAndSends() {
    assertThatThrownBy(() -> new TwilioOtpSmsSender(" ", "tok", "+1", (a, b, c, d, e, f) -> 200))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new TwilioOtpSmsSender("sid", " ", "+1", (a, b, c, d, e, f) -> 200))
        .isInstanceOf(IllegalStateException.class);

    // Missing from + messaging service: construct OK, send fails.
    TwilioOtpSmsSender noFrom =
        new TwilioOtpSmsSender("sid", "tok", "", "", "", (a, b, c, d, e, f) -> 200);
    assertThatThrownBy(() -> noFrom.sendOtp("+91", "1")).isInstanceOf(IllegalStateException.class);

    TwilioOtpSmsSender ok =
        new TwilioOtpSmsSender("sid", "tok", "+15551212", (a, b, c, d, e, f) -> 201);
    ok.sendOtp("+919876543210", "123456");

    assertThatThrownBy(
            () ->
                new TwilioOtpSmsSender("sid", "tok", "+1", (a, b, c, d, e, f) -> 500)
                    .sendOtp("+91", "1"))
        .isInstanceOf(IllegalStateException.class);

    assertThat(new TwilioOtpSmsSender("sid", "tok", "", "", "MGxxx", (a, b, c, d, e, f) -> 201))
        .isNotNull();

    // API key used as Basic username
    AtomicReference<String> user = new AtomicReference<>();
    new TwilioOtpSmsSender(
            "ACsid",
            "secret",
            "SKkey",
            "+1555",
            "",
            (u, p, from, msg, phone, otp) -> {
              user.set(u);
              return 201;
            })
        .sendOtp("+919876543210", "1");
    assertThat(user.get()).isEqualTo("SKkey");

    assertThatThrownBy(
            () ->
                new TwilioOtpSmsSender(
                        "sid",
                        "tok",
                        "+1555",
                        TwilioOtpSmsSender.defaultSender("http://127.0.0.1:1/"))
                    .sendOtp("+919876543210", "000000"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void httpPostUsesBasicAuthAndFormBody() throws Exception {
    AtomicReference<String> auth = new AtomicReference<>();
    AtomicReference<String> body = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        ex -> {
          auth.set(ex.getRequestHeaders().getFirst("Authorization"));
          body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          ex.sendResponseHeaders(201, -1);
          ex.close();
        });
    server.start();
    try {
      String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
      assertThat(
              TwilioOtpSmsSender.httpPost(
                  "ACsid", "secret", "+15551212", null, "+919876543210", "12 34", url))
          .isEqualTo(201);
      String expectedBasic =
          "Basic "
              + Base64.getEncoder().encodeToString("ACsid:secret".getBytes(StandardCharsets.UTF_8));
      assertThat(auth.get()).isEqualTo(expectedBasic);
      assertThat(body.get()).contains("To=%2B919876543210");
      assertThat(body.get()).contains("From=%2B15551212");
      assertThat(body.get()).contains("Body=Your+OTP+is+12+34");

      new TwilioOtpSmsSender(
              "ACsid",
              "secret",
              "+15551212",
              (sid, tok, from, msg, phone, otp) ->
                  TwilioOtpSmsSender.httpPost(sid, tok, from, msg, phone, otp, url))
          .sendOtp("+919876543210", "123456");
    } finally {
      server.stop(0);
    }
    assertThat(
            TwilioOtpSmsSender.httpPost(
                "sid", "tok", "+1", null, "+91", null, "http://127.0.0.1:1/"))
        .isEqualTo(599);
  }

  @Test
  void interruptedHttpPostReturns599() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        ex -> {
          try {
            Thread.sleep(5000);
          } catch (InterruptedException ignored) {
          }
          ex.sendResponseHeaders(200, -1);
          ex.close();
        });
    server.start();
    try {
      String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
      Thread.currentThread().interrupt();
      assertThat(TwilioOtpSmsSender.httpPost("u", "p", "+1", null, "+91", "1", url)).isEqualTo(599);
      assertThat(Thread.interrupted()).isTrue();
    } finally {
      server.stop(0);
    }
  }

  @Test
  void prefersMessagingServiceSidOverFrom() throws Exception {
    AtomicReference<String> body = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        ex -> {
          body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          ex.sendResponseHeaders(201, -1);
          ex.close();
        });
    server.start();
    try {
      String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
      TwilioOtpSmsSender.httpPost("sid", "tok", "+1555", "MG123", "+919999999999", "999999", url);
      assertThat(body.get()).contains("MessagingServiceSid=MG123");
      assertThat(body.get()).doesNotContain("From=");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void publicConstructorAndDefaultSender() {
    TwilioOtpSmsSender wired = new TwilioOtpSmsSender("ACsid", "tok", "SKkey", "+15551212", "");
    assertThat(TwilioOtpSmsSender.messagesUrl("ACsid"))
        .isEqualTo("https://api.twilio.com/2010-04-01/Accounts/ACsid/Messages.json");
    assertThat(TwilioOtpSmsSender.defaultSender("http://127.0.0.1:1/")).isNotNull();

    TwilioOtpSmsSender placeholderFrom =
        new TwilioOtpSmsSender("ACsid", "tok", "", "", "", (a, b, c, d, e, f) -> 200);
    assertThatThrownBy(() -> placeholderFrom.sendOtp("+91", "1"))
        .isInstanceOf(IllegalStateException.class);

    new TwilioOtpSmsSender(
            "ACsid",
            "tok",
            "  ",
            "+1555",
            null,
            (u, p, f, m, ph, o) -> {
              assertThat(u).isEqualTo("ACsid");
              return 201;
            })
        .sendOtp("+919876543210", "1");

    new TwilioOtpSmsSender("ACsid", "tok", null, "+1555", null, (a, b, c, d, e, f) -> 201)
        .sendOtp("+91", "9");

    // fromNumber null → empty string branch
    new TwilioOtpSmsSender("ACsid", "tok", "", null, "MGxxx", (a, b, c, d, e, f) -> 201)
        .sendOtp("+91", "9");

    assertThat(TwilioOtpSmsSender.httpPost("u", "p", "+1", "  ", "+91", "1", "http://127.0.0.1:1/"))
        .isEqualTo(599);

    assertThatThrownBy(() -> wired.sendOtp(null, "1")).isInstanceOf(NullPointerException.class);

    assertThatThrownBy(
            () -> new TwilioOtpSmsSender(null, "tok", "", "+1", "", (a, b, c, d, e, f) -> 200))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () -> new TwilioOtpSmsSender("sid", null, "", "+1", "", (a, b, c, d, e, f) -> 200))
        .isInstanceOf(IllegalStateException.class);

    // blank from with messaging SID present — send OK
    new TwilioOtpSmsSender("sid", "tok", "", "  ", "MGxxx", (a, b, c, d, e, f) -> 201)
        .sendOtp("+91", "1");

    // enc(null) via httpPost phone path
    assertThat(TwilioOtpSmsSender.httpPost("u", "p", "+1", null, null, "1", "http://127.0.0.1:1/"))
        .isEqualTo(599);
  }
}
