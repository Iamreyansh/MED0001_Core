package com.nammamedmate.notification.adapter.out.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.notification.application.port.out.FcmClientPort;
import com.nammamedmate.notification.application.port.out.TwilioClientPort;
import com.nammamedmate.notification.domain.PushPriority;
import com.nammamedmate.security.RsaKeyLoader;
import io.jsonwebtoken.Jwts;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

/** HTTP vendor adapters used in deployed profiles. Secrets are required at construction. */
public final class HttpVendorClients {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
  private static final String DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token";

  public interface Poster {
    int post(String url, String authHeader, String jsonBody);
  }

  /** OAuth2 access-token supplier (injectable for tests). */
  @FunctionalInterface
  public interface AccessTokenSource {
    String accessToken();
  }

  public interface HttpExchange {
    record Response(int status, String body) {}

    Response post(String url, String contentType, String body, String authorization);
  }

  private HttpVendorClients() {}

  public static Poster jdkPoster() {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    return (url, auth, body) -> {
      try {
        HttpRequest.Builder b =
            HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body));
        if (auth != null && !auth.isBlank()) {
          b.header("Authorization", auth);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
      } catch (IOException | InterruptedException e) {
        return 599;
      }
    };
  }

  static HttpExchange jdkExchange() {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    return (url, contentType, body, authorization) -> {
      try {
        HttpRequest.Builder b =
            HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", contentType == null ? "application/json" : contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        if (authorization != null && !authorization.isBlank()) {
          b.header("Authorization", authorization);
        }
        HttpResponse<String> resp = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new HttpExchange.Response(resp.statusCode(), resp.body());
      } catch (IOException | InterruptedException e) {
        return new HttpExchange.Response(599, "");
      }
    };
  }

  static void requireSecret(String name, String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " must be injected in deployed profiles");
    }
  }

  public static final class HttpTwilioClient implements TwilioClientPort {
    private final String accountSid;
    private final String basicAuth;
    private final Poster poster;

    public HttpTwilioClient(String accountSid, String authToken) {
      this(accountSid, authToken, null, jdkPoster());
    }

    public HttpTwilioClient(String accountSid, String authToken, Poster poster) {
      this(accountSid, authToken, null, poster);
    }

    public HttpTwilioClient(String accountSid, String authToken, String apiKey, Poster poster) {
      requireSecret("medmate.twilio.account-sid", accountSid);
      requireSecret("medmate.twilio.auth-token", authToken);
      String user = apiKey != null && !apiKey.isBlank() ? apiKey.trim() : accountSid.trim();
      this.accountSid = accountSid.trim();
      this.basicAuth =
          "Basic "
              + java.util.Base64.getEncoder()
                  .encodeToString((user + ":" + authToken.trim()).getBytes(StandardCharsets.UTF_8));
      this.poster = poster;
    }

    @Override
    public SendResult send(SendRequest request) {
      int code =
          poster.post(
              "https://api.twilio.com/2010-04-01/Accounts/" + accountSid + "/Messages.json",
              basicAuth,
              "{\"to\":\"" + request.toPhone() + "\"}");
      return code < 400 ? SendResult.ok("twilio_" + Ids.newId()) : SendResult.fail("HTTP " + code);
    }
  }

  /** FCM HTTP v1 — OAuth2 service-account access token + messages:send. */
  public static final class HttpFcmClient implements FcmClientPort {
    private final String projectId;
    private final Poster poster;
    private final AccessTokenSource tokens;

    public HttpFcmClient(String projectId, String serviceAccountJson) {
      this(projectId, serviceAccountJson, jdkPoster(), null);
    }

    public HttpFcmClient(String projectId, String serviceAccountJson, Poster poster) {
      this(projectId, serviceAccountJson, poster, null);
    }

    public HttpFcmClient(
        String projectId, String serviceAccountJson, Poster poster, AccessTokenSource tokens) {
      requireSecret("medmate.fcm.project-id", projectId);
      requireSecret("medmate.fcm.service-account-json", serviceAccountJson);
      this.projectId = projectId.trim();
      this.poster = poster;
      this.tokens =
          tokens != null
              ? tokens
              : cachingTokenSource(parseServiceAccount(serviceAccountJson), jdkExchange());
    }

    /** Test constructor that skips SA JSON parsing when a token source is provided. */
    HttpFcmClient(String projectId, Poster poster, AccessTokenSource tokens) {
      requireSecret("medmate.fcm.project-id", projectId);
      this.projectId = projectId.trim();
      this.poster = poster;
      this.tokens = tokens;
    }

    @Override
    public PushResult send(PushRequest request) {
      String accessToken;
      try {
        accessToken = tokens.accessToken();
      } catch (RuntimeException e) {
        return PushResult.fail("TOKEN", e.getMessage() == null ? "token failed" : e.getMessage());
      }
      int code =
          poster.post(
              "https://fcm.googleapis.com/v1/projects/" + projectId + "/messages:send",
              "Bearer " + accessToken,
              buildMessageJson(request));
      return code < 400
          ? PushResult.ok("fcm_" + Ids.newId())
          : PushResult.fail("HTTP_" + code, "FCM HTTP " + code);
    }

    static String buildMessageJson(PushRequest request) {
      StringBuilder msg = new StringBuilder(256);
      msg.append("{\"message\":{");
      msg.append("\"token\":\"").append(jsonEscape(request.token())).append('"');
      if (!request.silent()) {
        msg.append(",\"notification\":{");
        msg.append("\"title\":\"").append(jsonEscape(request.title())).append('"');
        msg.append(",\"body\":\"").append(jsonEscape(request.body())).append('"');
        if (request.imageUrl() != null && !request.imageUrl().isBlank()) {
          msg.append(",\"image\":\"").append(jsonEscape(request.imageUrl())).append('"');
        }
        msg.append('}');
      }
      Map<String, String> data = request.data();
      if (!data.isEmpty()) {
        msg.append(",\"data\":{");
        boolean first = true;
        for (Map.Entry<String, String> e : data.entrySet()) {
          if (!first) {
            msg.append(',');
          }
          first = false;
          msg.append('"')
              .append(jsonEscape(e.getKey()))
              .append("\":\"")
              .append(jsonEscape(e.getValue()))
              .append('"');
        }
        msg.append('}');
      }
      PushPriority priority = request.priority() == null ? PushPriority.NORMAL : request.priority();
      msg.append(",\"android\":{\"priority\":\"")
          .append(priority == PushPriority.HIGH ? "HIGH" : "NORMAL")
          .append("\"}");
      msg.append("}}");
      return msg.toString();
    }

    static String jsonEscape(String raw) {
      if (raw == null) {
        return "";
      }
      return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static ServiceAccount parseServiceAccount(String json) {
      try {
        JsonNode root = MAPPER.readTree(json);
        String email = text(root, "client_email");
        String privateKeyPem = text(root, "private_key");
        String tokenUri =
            root.hasNonNull("token_uri") && !root.get("token_uri").asText().isBlank()
                ? root.get("token_uri").asText()
                : DEFAULT_TOKEN_URI;
        PrivateKey key = RsaKeyLoader.loadPrivateKeyPem(privateKeyPem);
        return new ServiceAccount(email, key, tokenUri);
      } catch (IOException | IllegalArgumentException e) {
        throw new IllegalStateException("Invalid medmate.fcm.service-account-json", e);
      }
    }

    static AccessTokenSource cachingTokenSource(ServiceAccount sa, HttpExchange exchange) {
      return new CachingAccessTokenSource(sa, exchange);
    }

    static String fetchAccessToken(ServiceAccount sa, HttpExchange exchange) {
      Instant now = Instant.now();
      String assertion =
          Jwts.builder()
              .issuer(sa.clientEmail())
              .subject(sa.clientEmail())
              .audience()
              .add(sa.tokenUri())
              .and()
              .claim("scope", FCM_SCOPE)
              .issuedAt(Date.from(now))
              .expiration(Date.from(now.plusSeconds(3600)))
              .signWith(sa.privateKey())
              .compact();
      String form =
          "grant_type="
              + URLEncoder.encode(
                  "urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
              + "&assertion="
              + URLEncoder.encode(assertion, StandardCharsets.UTF_8);
      HttpExchange.Response resp =
          exchange.post(sa.tokenUri(), "application/x-www-form-urlencoded", form, null);
      if (resp.status() >= 400) {
        throw new IllegalStateException("Google token HTTP " + resp.status());
      }
      try {
        JsonNode body = MAPPER.readTree(resp.body() == null ? "{}" : resp.body());
        JsonNode token = body.get("access_token");
        if (token == null || token.asText().isBlank()) {
          throw new IllegalStateException("Google token response missing access_token");
        }
        return token.asText();
      } catch (IOException e) {
        throw new IllegalStateException("Google token response invalid", e);
      }
    }

    private static String text(JsonNode node, String field) {
      JsonNode v = node.get(field);
      if (v == null || v.isNull() || v.asText().isBlank()) {
        throw new IllegalStateException("service account missing " + field);
      }
      return v.asText();
    }

    record ServiceAccount(String clientEmail, PrivateKey privateKey, String tokenUri) {}

    static final class CachingAccessTokenSource implements AccessTokenSource {
      private final ServiceAccount sa;
      private final HttpExchange exchange;
      private String cached;
      private long expiresAtEpochMs;

      CachingAccessTokenSource(ServiceAccount sa, HttpExchange exchange) {
        this.sa = sa;
        this.exchange = exchange;
      }

      @Override
      public synchronized String accessToken() {
        long now = System.currentTimeMillis();
        if (cached != null && now < expiresAtEpochMs) {
          return cached;
        }
        cached = fetchAccessToken(sa, exchange);
        // Refresh 60s early; Google tokens are typically 3600s.
        expiresAtEpochMs = now + 3500_000L;
        return cached;
      }
    }
  }
}
