package com.nammamedmate.notification.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.notification.application.port.out.FcmClientPort;
import com.nammamedmate.notification.application.port.out.TwilioClientPort;
import com.nammamedmate.notification.domain.PushPriority;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HttpVendorClientsTest {

  @Test
  void requireSecretRejectsBlank() {
    assertThatThrownBy(() -> HttpVendorClients.requireSecret("x", " "))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> HttpVendorClients.requireSecret("x", null))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void twilioClientUsesPoster() {
    HttpVendorClients.HttpTwilioClient client =
        new HttpVendorClients.HttpTwilioClient("sid", "token", (url, auth, body) -> 200);
    TwilioClientPort.SendResult ok =
        client.send(new TwilioClientPort.SendRequest("+919999999999", "NMMATE", "hi", Map.of()));
    assertThat(ok.success()).isTrue();
    assertThat(ok.messageId()).startsWith("twilio_");

    HttpVendorClients.HttpTwilioClient withApiKey =
        new HttpVendorClients.HttpTwilioClient("sid", "token", "api-key", (url, auth, body) -> 200);
    assertThat(
            withApiKey
                .send(new TwilioClientPort.SendRequest("+919999999999", "NMMATE", "hi", Map.of()))
                .success())
        .isTrue();

    HttpVendorClients.HttpTwilioClient blankApiKey =
        new HttpVendorClients.HttpTwilioClient("sid", "token", "  ", (url, auth, body) -> 200);
    assertThat(
            blankApiKey
                .send(new TwilioClientPort.SendRequest("+919999999999", "NMMATE", "hi", Map.of()))
                .success())
        .isTrue();

    HttpVendorClients.HttpTwilioClient fail =
        new HttpVendorClients.HttpTwilioClient("sid", "token", (url, auth, body) -> 500);
    assertThat(
            fail.send(new TwilioClientPort.SendRequest("+919999999999", "NMMATE", "hi", Map.of()))
                .success())
        .isFalse();

    assertThat(new HttpVendorClients.HttpTwilioClient("sid", "token")).isNotNull();
  }

  @Test
  void fcmClientUsesBearerAndV1Url() {
    AtomicReference<String> url = new AtomicReference<>();
    AtomicReference<String> auth = new AtomicReference<>();
    AtomicReference<String> body = new AtomicReference<>();
    HttpVendorClients.HttpFcmClient client =
        new HttpVendorClients.HttpFcmClient(
            "medmate-app",
            (u, a, b) -> {
              url.set(u);
              auth.set(a);
              body.set(b);
              return 200;
            },
            () -> "ya29.test");
    FcmClientPort.PushResult ok =
        client.send(
            new FcmClientPort.PushRequest(
                "tok",
                "Hello",
                "World",
                Map.of("k", "v"),
                "https://cdn/x.png",
                null,
                PushPriority.HIGH,
                false));
    assertThat(ok.success()).isTrue();
    assertThat(url.get())
        .isEqualTo("https://fcm.googleapis.com/v1/projects/medmate-app/messages:send");
    assertThat(auth.get()).isEqualTo("Bearer ya29.test");
    assertThat(body.get()).contains("\"token\":\"tok\"");
    assertThat(body.get()).contains("\"title\":\"Hello\"");
    assertThat(body.get()).contains("\"priority\":\"HIGH\"");
    assertThat(body.get()).contains("\"k\":\"v\"");

    HttpVendorClients.HttpFcmClient fail =
        new HttpVendorClients.HttpFcmClient("p", (u, a, b) -> 500, () -> "t");
    assertThat(
            fail.send(new FcmClientPort.PushRequest("tok", "t", "b", null, null, null, null, false))
                .success())
        .isFalse();
  }

  @Test
  void fcmTokenFailureAndSilentPayload() {
    HttpVendorClients.HttpFcmClient tokenFail =
        new HttpVendorClients.HttpFcmClient(
            "p",
            (u, a, b) -> 200,
            () -> {
              throw new IllegalStateException("boom");
            });
    assertThat(
            tokenFail
                .send(new FcmClientPort.PushRequest("tok", "t", "b", null, null, null, null, false))
                .errorCode())
        .isEqualTo("TOKEN");

    String silent =
        HttpVendorClients.HttpFcmClient.buildMessageJson(
            new FcmClientPort.PushRequest(
                "tok", "t", "b", Map.of(), null, null, PushPriority.NORMAL, true));
    assertThat(silent).doesNotContain("notification");
    assertThat(silent).contains("\"priority\":\"NORMAL\"");
    assertThat(HttpVendorClients.HttpFcmClient.jsonEscape("a\"b\\c")).isEqualTo("a\\\"b\\\\c");
    assertThat(HttpVendorClients.HttpFcmClient.jsonEscape(null)).isEmpty();

    String multi =
        HttpVendorClients.HttpFcmClient.buildMessageJson(
            new FcmClientPort.PushRequest(
                "tok", "t", "b", Map.of("a", "1", "b", "2"), "  ", null, null, false));
    assertThat(multi).contains("\"a\":\"1\"");
    assertThat(multi).contains("\"b\":\"2\"");
    assertThat(multi).doesNotContain("\"image\"");

    String emptyData =
        HttpVendorClients.HttpFcmClient.buildMessageJson(
            new FcmClientPort.PushRequest(
                "tok", "t", "b", Map.of(), "https://cdn/x.png", null, PushPriority.HIGH, false));
    assertThat(emptyData).contains("\"image\"");
    assertThat(emptyData).doesNotContain("\"data\"");

    HttpVendorClients.HttpFcmClient nullMsg =
        new HttpVendorClients.HttpFcmClient(
            "p",
            (u, a, b) -> 200,
            () -> {
              throw new IllegalStateException();
            });
    assertThat(
            nullMsg
                .send(new FcmClientPort.PushRequest("tok", "t", "b", null, null, null, null, false))
                .errorMessage())
        .isEqualTo("token failed");
  }

  @Test
  void constructorsRequireSecrets() {
    assertThatThrownBy(() -> new HttpVendorClients.HttpTwilioClient("", "t"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new HttpVendorClients.HttpFcmClient("", "{}"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new HttpVendorClients.HttpFcmClient("proj", " "))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new HttpVendorClients.HttpFcmClient("proj", "{not-json}"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void fetchAccessTokenWithMockExchangeAndCaches() throws Exception {
    KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
    gen.initialize(2048);
    KeyPair pair = gen.generateKeyPair();
    String pem =
        "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(pair.getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----\n";
    String saJson =
        "{\"client_email\":\"sa@medmate.iam.gserviceaccount.com\",\"private_key\":"
            + quoteJson(pem)
            + ",\"token_uri\":\"https://oauth2.googleapis.com/token\"}";
    HttpVendorClients.HttpFcmClient.ServiceAccount sa =
        HttpVendorClients.HttpFcmClient.parseServiceAccount(saJson);

    AtomicInteger calls = new AtomicInteger();
    HttpVendorClients.HttpExchange exchange =
        (url, contentType, body, authorization) -> {
          calls.incrementAndGet();
          assertThat(url).isEqualTo("https://oauth2.googleapis.com/token");
          assertThat(contentType).isEqualTo("application/x-www-form-urlencoded");
          assertThat(body).contains("grant_type=");
          assertThat(body).contains("assertion=");
          return new HttpVendorClients.HttpExchange.Response(
              200, "{\"access_token\":\"ya29.cached\",\"expires_in\":3600}");
        };
    HttpVendorClients.AccessTokenSource source =
        HttpVendorClients.HttpFcmClient.cachingTokenSource(sa, exchange);
    assertThat(source.accessToken()).isEqualTo("ya29.cached");
    assertThat(source.accessToken()).isEqualTo("ya29.cached");
    assertThat(calls.get()).isEqualTo(1);

    var field = source.getClass().getDeclaredField("expiresAtEpochMs");
    field.setAccessible(true);
    field.setLong(source, 0L);
    assertThat(source.accessToken()).isEqualTo("ya29.cached");
    assertThat(calls.get()).isEqualTo(2);

    assertThatThrownBy(
            () ->
                HttpVendorClients.HttpFcmClient.fetchAccessToken(
                    sa, (u, c, b, a) -> new HttpVendorClients.HttpExchange.Response(401, "{}")))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                HttpVendorClients.HttpFcmClient.fetchAccessToken(
                    sa, (u, c, b, a) -> new HttpVendorClients.HttpExchange.Response(200, "{}")))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                HttpVendorClients.HttpFcmClient.fetchAccessToken(
                    sa,
                    (u, c, b, a) ->
                        new HttpVendorClients.HttpExchange.Response(
                            200, "{\"access_token\":\"  \"}")))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                HttpVendorClients.HttpFcmClient.fetchAccessToken(
                    sa,
                    (u, c, b, a) -> new HttpVendorClients.HttpExchange.Response(200, "not-json")))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                HttpVendorClients.HttpFcmClient.fetchAccessToken(
                    sa, (u, c, b, a) -> new HttpVendorClients.HttpExchange.Response(200, null)))
        .isInstanceOf(IllegalStateException.class);

    // Full client wires SA JSON → token source → send
    HttpVendorClients.HttpFcmClient live =
        new HttpVendorClients.HttpFcmClient("proj", saJson, (u, a, b) -> 200, source);
    assertThat(
            live.send(new FcmClientPort.PushRequest("tok", "t", "b", null, null, null, null, false))
                .success())
        .isTrue();

    // Production constructors (parse SA at build time; token fetch deferred)
    assertThat(new HttpVendorClients.HttpFcmClient("proj", saJson)).isNotNull();
    assertThat(new HttpVendorClients.HttpFcmClient("proj", saJson, (u, a, b) -> 200)).isNotNull();
  }

  @Test
  void jdkExchangeAndPosterReachLocalServer() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/ok",
        ex -> {
          byte[] body = "{\"access_token\":\"local\"}".getBytes(StandardCharsets.UTF_8);
          ex.getResponseHeaders().add("Content-Type", "application/json");
          ex.sendResponseHeaders(200, body.length);
          ex.getResponseBody().write(body);
          ex.close();
        });
    server.start();
    try {
      String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/ok";
      assertThat(HttpVendorClients.jdkPoster().post(base, "Bearer x", "{}")).isEqualTo(200);
      assertThat(HttpVendorClients.jdkPoster().post(base, "  ", "{}")).isEqualTo(200);
      assertThat(HttpVendorClients.jdkPoster().post(base, null, null)).isEqualTo(200);
      assertThat(
              HttpVendorClients.jdkExchange().post(base, "application/json", "{}", null).status())
          .isEqualTo(200);
      assertThat(HttpVendorClients.jdkExchange().post(base, null, null, "  ").status())
          .isEqualTo(200);
      assertThat(HttpVendorClients.jdkPoster().post("http://127.0.0.1:1/", null, null))
          .isEqualTo(599);
      assertThat(
              HttpVendorClients.jdkExchange()
                  .post("http://127.0.0.1:1/", "application/json", "{}", "Bearer x")
                  .status())
          .isEqualTo(599);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void parseServiceAccountDefaultsTokenUri() throws Exception {
    KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
    gen.initialize(2048);
    KeyPair pair = gen.generateKeyPair();
    String pem =
        "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(pair.getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----\n";
    var sa =
        HttpVendorClients.HttpFcmClient.parseServiceAccount(
            "{\"client_email\":\"a@b.com\",\"private_key\":" + quoteJson(pem) + "}");
    assertThat(sa.tokenUri()).isEqualTo("https://oauth2.googleapis.com/token");
    assertThatThrownBy(
            () -> HttpVendorClients.HttpFcmClient.parseServiceAccount("{\"client_email\":\"a\"}"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                HttpVendorClients.HttpFcmClient.parseServiceAccount(
                    "{\"client_email\":null,\"private_key\":" + quoteJson(pem) + "}"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                HttpVendorClients.HttpFcmClient.parseServiceAccount(
                    "{\"client_email\":\"  \",\"private_key\":" + quoteJson(pem) + "}"))
        .isInstanceOf(IllegalStateException.class);
    var saBlankUri =
        HttpVendorClients.HttpFcmClient.parseServiceAccount(
            "{\"client_email\":\"a@b.com\",\"private_key\":"
                + quoteJson(pem)
                + ",\"token_uri\":\"  \"}");
    assertThat(saBlankUri.tokenUri()).isEqualTo("https://oauth2.googleapis.com/token");
  }

  private static String quoteJson(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
  }
}
