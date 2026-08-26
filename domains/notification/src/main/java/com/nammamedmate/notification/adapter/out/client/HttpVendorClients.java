package com.nammamedmate.notification.adapter.out.client;

import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.notification.application.port.out.FcmClientPort;
import com.nammamedmate.notification.application.port.out.MetaWhatsAppClientPort;
import com.nammamedmate.notification.application.port.out.Msg91ClientPort;
import com.nammamedmate.notification.application.port.out.SendGridClientPort;
import com.nammamedmate.notification.application.port.out.SesClientPort;
import com.nammamedmate.notification.application.port.out.TwilioClientPort;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** HTTP vendor adapters used in deployed profiles. Secrets are required at construction. */
public final class HttpVendorClients {

  public interface Poster {
    int post(String url, String authHeader, String jsonBody);
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

  static void requireSecret(String name, String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " must be injected in deployed profiles");
    }
  }

  public static final class HttpMsg91Client implements Msg91ClientPort {
    private final String authKey;
    private final Poster poster;

    public HttpMsg91Client(String authKey) {
      this(authKey, jdkPoster());
    }

    public HttpMsg91Client(String authKey, Poster poster) {
      requireSecret("medmate.msg91.auth-key", authKey);
      this.authKey = authKey;
      this.poster = poster;
    }

    @Override
    public boolean isOnDnd(String toPhone) {
      return false;
    }

    @Override
    public SendResult send(SendRequest request) {
      int code =
          poster.post(
              "https://api.msg91.com/api/v5/flow/",
              authKey,
              "{\"mobiles\":\"" + request.toPhone() + "\"}");
      return code < 400 ? SendResult.ok("msg91_" + Ids.newId()) : SendResult.fail("HTTP " + code);
    }
  }

  public static final class HttpTwilioClient implements TwilioClientPort {
    private final String token;
    private final Poster poster;

    public HttpTwilioClient(String accountSid, String authToken) {
      this(accountSid, authToken, jdkPoster());
    }

    public HttpTwilioClient(String accountSid, String authToken, Poster poster) {
      requireSecret("medmate.twilio.account-sid", accountSid);
      requireSecret("medmate.twilio.auth-token", authToken);
      this.token = accountSid + ":" + authToken;
      this.poster = poster;
    }

    @Override
    public SendResult send(SendRequest request) {
      int code =
          poster.post(
              "https://api.twilio.com/2010-04-01/Accounts.json",
              "Basic " + token,
              "{\"to\":\"" + request.toPhone() + "\"}");
      return code < 400 ? SendResult.ok("twilio_" + Ids.newId()) : SendResult.fail("HTTP " + code);
    }
  }

  public static final class HttpFcmClient implements FcmClientPort {
    private final String serverKey;
    private final Poster poster;

    public HttpFcmClient(String serverKey) {
      this(serverKey, jdkPoster());
    }

    public HttpFcmClient(String serverKey, Poster poster) {
      requireSecret("medmate.fcm.server-key", serverKey);
      this.serverKey = serverKey;
      this.poster = poster;
    }

    @Override
    public PushResult send(PushRequest request) {
      int code =
          poster.post(
              "https://fcm.googleapis.com/fcm/send",
              "key=" + serverKey,
              "{\"to\":\"" + request.token() + "\"}");
      return code < 400
          ? PushResult.ok("fcm_" + Ids.newId())
          : PushResult.fail("HTTP_" + code, "FCM HTTP " + code);
    }
  }

  public static final class HttpSesClient implements SesClientPort {
    private final Poster poster;

    public HttpSesClient(String region) {
      this(region, jdkPoster());
    }

    public HttpSesClient(String region, Poster poster) {
      requireSecret("medmate.ses.region", region);
      this.poster = poster;
    }

    @Override
    public SendResult send(SendRequest request) {
      int code =
          poster.post(
              "https://email.amazonaws.com/", null, "{\"to\":\"" + request.toEmail() + "\"}");
      return code < 400 ? SendResult.ok("ses_" + Ids.newId()) : SendResult.fail("HTTP " + code);
    }
  }

  public static final class HttpSendGridClient implements SendGridClientPort {
    private final String apiKey;
    private final Poster poster;

    public HttpSendGridClient(String apiKey) {
      this(apiKey, jdkPoster());
    }

    public HttpSendGridClient(String apiKey, Poster poster) {
      requireSecret("medmate.sendgrid.api-key", apiKey);
      this.apiKey = apiKey;
      this.poster = poster;
    }

    @Override
    public SendResult send(SendRequest request) {
      int code =
          poster.post(
              "https://api.sendgrid.com/v3/mail/send",
              "Bearer " + apiKey,
              "{\"to\":\"" + request.toEmail() + "\"}");
      return code < 400 ? SendResult.ok("sg_" + Ids.newId()) : SendResult.fail("HTTP " + code);
    }
  }

  public static final class HttpMetaWhatsAppClient implements MetaWhatsAppClientPort {
    private final String token;
    private final String appSecret;
    private final Poster poster;

    public HttpMetaWhatsAppClient(String token, String appSecret) {
      this(token, appSecret, jdkPoster());
    }

    public HttpMetaWhatsAppClient(String token, String appSecret, Poster poster) {
      requireSecret("medmate.whatsapp.access-token", token);
      requireSecret("medmate.whatsapp.app-secret", appSecret);
      this.token = token;
      this.appSecret = appSecret;
      this.poster = poster;
    }

    @Override
    public SendResult sendTemplate(SendRequest request) {
      int code =
          poster.post(
              "https://graph.facebook.com/v21.0/messages",
              "Bearer " + token,
              "{\"to\":\"" + request.toPhone() + "\"}");
      return code < 400 ? SendResult.ok("wamid." + Ids.newId()) : SendResult.fail("HTTP " + code);
    }

    @Override
    public SubmitTemplateResult submitTemplate(SubmitTemplateRequest request) {
      int code =
          poster.post(
              "https://graph.facebook.com/v21.0/message_templates",
              "Bearer " + token,
              "{\"name\":\"" + request.name() + "\"}");
      return code < 400
          ? SubmitTemplateResult.ok("meta_" + request.name())
          : SubmitTemplateResult.fail("HTTP " + code);
    }

    @Override
    public boolean verifyWebhookSignature(String signatureHeader, byte[] rawBody) {
      return com.nammamedmate.notification.application.NotificationWebhookAuth.verify(
          appSecret, signatureHeader, rawBody);
    }
  }
}
