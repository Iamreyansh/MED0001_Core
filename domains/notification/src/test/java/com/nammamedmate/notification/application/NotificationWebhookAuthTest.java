package com.nammamedmate.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.error.AppException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class NotificationWebhookAuthTest {

  @Test
  void signsAndVerifiesSms() {
    NotificationWebhookAuth auth = new NotificationWebhookAuth("secret");
    byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
    String sig = auth.signSms(body);
    auth.requireSms(sig, body);
    assertThatThrownBy(() -> auth.requireSms("bad", body)).hasMessageContaining("SMS webhook");
  }

  @Test
  void blankSecretFallsBackToDefault() {
    NotificationWebhookAuth auth = new NotificationWebhookAuth(null);
    NotificationWebhookAuth blank = new NotificationWebhookAuth("  ");
    byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
    auth.requireSms(auth.signSms(body), body);
    blank.requireSms(blank.signSms(body), body);
  }

  @Test
  void rejectsMissingOrBadSignatures() {
    NotificationWebhookAuth auth = new NotificationWebhookAuth("sms-secret");
    byte[] body = "payload".getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(() -> auth.requireSms(null, body))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_SIGNATURE");
    assertThatThrownBy(() -> auth.requireSms("sha256=deadbeef", body))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(() -> auth.requireSms("  ", body)).isInstanceOf(AppException.class);
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
    NotificationWebhookAuth auth = new NotificationWebhookAuth("custom-sms");
    byte[] body = "x".getBytes(StandardCharsets.UTF_8);
    assertThat(auth.signSms(body))
        .isEqualTo("sha256=" + NotificationWebhookAuth.hmacHex("custom-sms", body));
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
  void validateSecretsAndVendorKeys() {
    assertThatThrownBy(() -> NotificationWebhookAuth.validateSecretsForDeployedProfile("", null))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                NotificationWebhookAuth.validateSecretsForDeployedProfile(
                    NotificationWebhookAuth.DEFAULT_SMS_SECRET, null))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () -> NotificationWebhookAuth.validateSecretsForDeployedProfile("replace_me", null))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> NotificationWebhookAuth.validateSecretsForDeployedProfile(null, null))
        .isInstanceOf(IllegalStateException.class);
    assertThatCode(
            () ->
                NotificationWebhookAuth.validateSecretsForDeployedProfile("prod-sms-secret", null))
        .doesNotThrowAnyException();

    assertThatThrownBy(
            () ->
                NotificationWebhookAuth.validateVendorKeysForDeployedProfile(
                    "", "tok", "proj", "{\"type\":\"service_account\"}"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                NotificationWebhookAuth.validateVendorKeysForDeployedProfile(
                    "sid", "tok", "proj", ""))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                NotificationWebhookAuth.validateVendorKeysForDeployedProfile(
                    "replace_me", "b", "c", "d"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                NotificationWebhookAuth.validateVendorKeysForDeployedProfile(
                    "a", "replace_me", "c", "d"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                NotificationWebhookAuth.validateVendorKeysForDeployedProfile(
                    "a", "b", "replace_me", "d"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                NotificationWebhookAuth.validateVendorKeysForDeployedProfile(
                    "a", "b", "c", "replace_me"))
        .isInstanceOf(IllegalStateException.class);
    assertThatCode(
            () ->
                NotificationWebhookAuth.validateVendorKeysForDeployedProfile(
                    "sid",
                    "tok",
                    "my-project",
                    "{\"client_email\":\"x@y.iam.gserviceaccount.com\"}"))
        .doesNotThrowAnyException();

    assertThat(NotificationWebhookAuth.isPlaceholder("changeme")).isTrue();
    assertThat(NotificationWebhookAuth.isPlaceholder("ok")).isFalse();
    assertThat(NotificationWebhookAuth.isPlaceholder(null)).isTrue();
    assertThat(NotificationWebhookAuth.isPlaceholder(" ")).isTrue();
  }

  @Test
  void verifyStaticHelper() {
    byte[] body = "x".getBytes(StandardCharsets.UTF_8);
    String sig = "sha256=" + NotificationWebhookAuth.hmacHex("s", body);
    assertThat(NotificationWebhookAuth.verify("s", sig, body)).isTrue();
    assertThat(NotificationWebhookAuth.verify("s", null, body)).isFalse();
  }
}
