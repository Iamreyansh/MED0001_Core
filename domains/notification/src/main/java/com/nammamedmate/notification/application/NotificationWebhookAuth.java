package com.nammamedmate.notification.application;

import com.nammamedmate.kernel.error.AppException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** HMAC-SHA256 verification for SMS (MSG91) and email (SendGrid) webhooks. */
@Component
public class NotificationWebhookAuth {

  public static final String DEFAULT_SMS_SECRET = "test_sms_webhook_secret";
  public static final String DEFAULT_EMAIL_SECRET = "test_email_webhook_secret";

  private final String smsSecret;
  private final String emailSecret;

  public NotificationWebhookAuth(
      @Value("${medmate.sms.webhook-secret:}") String smsSecret,
      @Value("${medmate.email.webhook-secret:}") String emailSecret) {
    this.smsSecret =
        smsSecret == null || smsSecret.isBlank() ? DEFAULT_SMS_SECRET : smsSecret.trim();
    this.emailSecret =
        emailSecret == null || emailSecret.isBlank() ? DEFAULT_EMAIL_SECRET : emailSecret.trim();
  }

  public void requireSms(String signatureHeader, byte[] rawBody) {
    if (!verify(smsSecret, signatureHeader, rawBody)) {
      throw new AppException("INVALID_SIGNATURE", "SMS webhook signature verification failed", 403);
    }
  }

  public void requireEmail(String signatureHeader, byte[] rawBody) {
    if (!verify(emailSecret, signatureHeader, rawBody)) {
      throw new AppException(
          "INVALID_SIGNATURE", "Email webhook signature verification failed", 403);
    }
  }

  public String signSms(byte[] rawBody) {
    return "sha256=" + hmacHex(smsSecret, rawBody);
  }

  public String signEmail(byte[] rawBody) {
    return "sha256=" + hmacHex(emailSecret, rawBody);
  }

  static boolean verify(String secret, String signatureHeader, byte[] rawBody) {
    if (signatureHeader == null || signatureHeader.isBlank()) {
      return false;
    }
    byte[] body = rawBody == null ? new byte[0] : rawBody;
    String expected = "sha256=" + hmacHex(secret, body);
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8),
        signatureHeader.trim().getBytes(StandardCharsets.UTF_8));
  }

  static String hmacHex(String secret, byte[] body) {
    return hmacHex(secret, body, "HmacSHA256");
  }

  /** Visible for coverage of the failure path with a bogus algorithm. */
  static String hmacHex(String secret, byte[] body, String algorithm) {
    try {
      Mac mac = Mac.getInstance(algorithm);
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), algorithm));
      return HexFormat.of().formatHex(mac.doFinal(body == null ? new byte[0] : body));
    } catch (Exception e) {
      throw new IllegalStateException("HMAC failed", e);
    }
  }
}
