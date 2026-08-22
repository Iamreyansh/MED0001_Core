package com.nammamedmate.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.error.AppException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class NotificationWebhookAuthTest {

  @Test
  void smsAndEmailSignaturesRoundTrip() {
    NotificationWebhookAuth auth = new NotificationWebhookAuth(null, null);
    byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
    auth.requireSms(auth.signSms(body), body);
    auth.requireEmail(auth.signEmail(body), body);

    NotificationWebhookAuth blank = new NotificationWebhookAuth("  ", "  ");
    blank.requireSms(blank.signSms(body), body);
    blank.requireEmail(blank.signEmail(body), body);
  }

  @Test
  void rejectsMissingOrBadSignatures() {
    NotificationWebhookAuth auth = new NotificationWebhookAuth("sms-secret", "email-secret");
    byte[] body = "payload".getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(() -> auth.requireSms(null, body))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_SIGNATURE");
    assertThatThrownBy(() -> auth.requireSms("sha256=deadbeef", body))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(() -> auth.requireEmail("  ", body)).isInstanceOf(AppException.class);
    assertThatThrownBy(() -> auth.requireEmail("sha256=bad", null))
        .isInstanceOf(AppException.class);
  }

  @Test
  void verifyHelperHandlesNullBody() {
    assertThat(NotificationWebhookAuth.verify("s", null, null)).isFalse();
    String sig = "sha256=" + NotificationWebhookAuth.hmacHex("s", new byte[0]);
    assertThat(NotificationWebhookAuth.verify("s", sig, null)).isTrue();
    assertThat(NotificationWebhookAuth.verify("s", sig, new byte[0])).isTrue();
  }

  @Test
  void configuredSecretsUsedForSigning() {
    NotificationWebhookAuth auth = new NotificationWebhookAuth("custom-sms", "custom-email");
    byte[] body = "x".getBytes(StandardCharsets.UTF_8);
    assertThat(auth.signSms(body))
        .isEqualTo("sha256=" + NotificationWebhookAuth.hmacHex("custom-sms", body));
    assertThat(auth.signEmail(body))
        .isEqualTo("sha256=" + NotificationWebhookAuth.hmacHex("custom-email", body));
  }

  @Test
  void hmacFailsOnUnknownAlgorithmAndAcceptsNullBody() {
    assertThat(NotificationWebhookAuth.hmacHex("s", null, "HmacSHA256")).isNotBlank();
    assertThatThrownBy(
            () -> NotificationWebhookAuth.hmacHex("s", new byte[0], "NoSuchMacAlgorithm"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("HMAC failed");
  }

  @Test
  void deployedSecretsRejectDefaults() {
    assertThatThrownBy(
            () ->
                NotificationWebhookAuth.validateSecretsForDeployedProfile(
                    NotificationWebhookAuth.DEFAULT_SMS_SECRET, "ok"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> NotificationWebhookAuth.validateSecretsForDeployedProfile("ok", " "))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> NotificationWebhookAuth.validateSecretsForDeployedProfile(null, "ok"))
        .isInstanceOf(IllegalStateException.class);
    NotificationWebhookAuth.validateSecretsForDeployedProfile("live-sms", "live-email");
  }
}
