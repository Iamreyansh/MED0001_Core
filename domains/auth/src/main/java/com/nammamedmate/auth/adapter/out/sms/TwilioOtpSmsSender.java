package com.nammamedmate.auth.adapter.out.sms;

import com.nammamedmate.auth.application.port.out.SmsSender;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Deployed OTP SMS via Twilio Messages API; fail-closed when credentials are missing. */
@Component
@Profile({"prod", "staging"})
public final class TwilioOtpSmsSender implements SmsSender {

  private final String accountSid;
  private final String authUser;
  private final String authPass;
  private final String fromNumber;
  private final String messagingServiceSid;
  private final Sender sender;

  @Autowired
  public TwilioOtpSmsSender(
      @Value("${medmate.twilio.account-sid:}") String accountSid,
      @Value("${medmate.twilio.auth-token:}") String authToken,
      @Value("${medmate.twilio.api-key:}") String apiKey,
      @Value("${medmate.twilio.from-number:}") String fromNumber,
      @Value("${medmate.twilio.messaging-service-sid:}") String messagingServiceSid) {
    this(
        accountSid,
        authToken,
        apiKey,
        fromNumber,
        messagingServiceSid,
        defaultSender(messagesUrl(accountSid)));
  }

  static String messagesUrl(String accountSid) {
    return "https://api.twilio.com/2010-04-01/Accounts/" + accountSid + "/Messages.json";
  }

  static Sender defaultSender(String url) {
    return (authUser, authPass, from, msgSid, phone, otp) ->
        httpPost(authUser, authPass, from, msgSid, phone, otp, url);
  }

  TwilioOtpSmsSender(
      String accountSid,
      String authToken,
      String apiKey,
      String fromNumber,
      String messagingServiceSid,
      Sender sender) {
    String sid = accountSid == null ? "" : accountSid.trim();
    String token = authToken == null ? "" : authToken.trim();
    if (sid.isEmpty()) {
      throw new IllegalStateException(
          "medmate.twilio.account-sid must be injected in deployed profiles");
    }
    if (token.isEmpty()) {
      throw new IllegalStateException(
          "medmate.twilio.auth-token must be injected in deployed profiles");
    }
    String key = apiKey == null ? "" : apiKey.trim();
    this.accountSid = sid;
    this.authUser = key.isEmpty() ? sid : key;
    this.authPass = token;
    String from = fromNumber == null ? "" : fromNumber.trim();
    this.fromNumber = from.isEmpty() ? null : from;
    String msg = messagingServiceSid == null ? "" : messagingServiceSid.trim();
    this.messagingServiceSid = msg.isEmpty() ? null : msg;
    this.sender = sender;
  }

  /** Package-private overload for tests that omit messaging-service-sid / api-key. */
  TwilioOtpSmsSender(String accountSid, String authToken, String fromNumber, Sender sender) {
    this(accountSid, authToken, "", fromNumber, "", sender);
  }

  @Override
  public void sendOtp(String phone, String otp) {
    Objects.requireNonNull(phone, "phone");
    if (fromNumber == null && messagingServiceSid == null) {
      throw new IllegalStateException(
          "medmate.twilio.from-number or messaging-service-sid must be set to send OTP");
    }
    int code = sender.send(authUser, authPass, fromNumber, messagingServiceSid, phone, otp);
    if (code >= 400) {
      throw new IllegalStateException("Twilio OTP send failed HTTP " + code);
    }
  }

  static int httpPost(
      String authUser,
      String authPass,
      String fromNumber,
      String messagingServiceSid,
      String phone,
      String otp,
      String url) {
    String safeOtp = otp == null ? "" : otp;
    String bodyText = "Your OTP is " + safeOtp;
    StringBuilder form = new StringBuilder();
    form.append("To=").append(enc(phone));
    if (messagingServiceSid != null && !messagingServiceSid.isBlank()) {
      form.append("&MessagingServiceSid=").append(enc(messagingServiceSid));
    } else {
      form.append("&From=").append(enc(fromNumber));
    }
    form.append("&Body=").append(enc(bodyText));
    String basic =
        Base64.getEncoder()
            .encodeToString((authUser + ":" + authPass).getBytes(StandardCharsets.UTF_8));
    try {
      HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(url))
              .timeout(Duration.ofSeconds(8))
              .header("Authorization", "Basic " + basic)
              .header("Content-Type", "application/x-www-form-urlencoded")
              .POST(HttpRequest.BodyPublishers.ofString(form.toString()))
              .build();
      return client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    } catch (IOException e) {
      return 599;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return 599;
    }
  }

  private static String enc(String value) {
    return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
  }

  @FunctionalInterface
  interface Sender {
    int send(
        String authUser,
        String authPass,
        String fromNumber,
        String messagingServiceSid,
        String phone,
        String otp);
  }
}
