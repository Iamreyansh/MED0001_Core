package com.nammamedmate.auth.adapter.out.sms;

import com.nammamedmate.auth.application.port.out.SmsSender;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Deployed OTP SMS — HTTP POST to MSG91; fail-closed when auth key is missing. */
@Component
@Profile({"prod", "staging"})
public final class Msg91OtpSmsSender implements SmsSender {

  private static final String OTP_URL = "https://api.msg91.com/api/v5/otp";

  private final String authKey;
  private final Sender sender;

  @Autowired
  public Msg91OtpSmsSender(@Value("${medmate.msg91.auth-key:}") String authKey) {
    this(authKey, defaultSender(OTP_URL));
  }

  static Sender defaultSender(String url) {
    return (phone, otp, key) -> httpPost(phone, otp, key, url);
  }

  Msg91OtpSmsSender(String authKey, Sender sender) {
    if (authKey == null || authKey.isBlank()) {
      throw new IllegalStateException(
          "medmate.msg91.auth-key must be injected in deployed profiles");
    }
    this.authKey = authKey;
    this.sender = sender;
  }

  @Override
  public void sendOtp(String phone, String otp) {
    Objects.requireNonNull(phone, "phone");
    int code = sender.send(phone, otp, authKey);
    if (code >= 400) {
      throw new IllegalStateException("MSG91 OTP send failed HTTP " + code);
    }
  }

  static int httpPost(String phone, String otp, String authKey, String url) {
    String mobile = phone.replaceAll("[^0-9]", "");
    String safeOtp = otp == null ? "" : otp.replace("\"", "");
    String body =
        "{\"mobile\":\""
            + mobile
            + "\",\"otp\":\""
            + safeOtp
            + "\",\"message\":\"Your OTP is "
            + safeOtp
            + "\"}";
    try {
      HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(url))
              .timeout(Duration.ofSeconds(8))
              .header("authkey", authKey)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      return client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    } catch (IOException | InterruptedException e) {
      return 599;
    }
  }

  @FunctionalInterface
  interface Sender {
    int send(String phone, String otp, String authKey);
  }
}
