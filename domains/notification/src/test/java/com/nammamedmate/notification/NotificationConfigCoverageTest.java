package com.nammamedmate.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NotificationConfigCoverageTest {

  @Test
  void deployedHttpBeansRequireSecretsAndConstruct() throws Exception {
    NotificationConfig cfg = new NotificationConfig();
    assertThat(cfg.notificationClock()).isNotNull();
    assertThat(cfg.stubFcmClient()).isNotNull();
    assertThat(cfg.stubTwilioClient()).isNotNull();
    assertThat(cfg.stubAttachmentFetcher()).isNotNull();
    assertThat(cfg.stubRecipientDisplayName().displayName(null, null)).isEmpty();
    assertThat(
            cfg.stubRecipientDisplayName()
                .displayName(java.util.UUID.randomUUID(), null)
                .orElseThrow())
        .isEqualTo("Recipient");

    java.security.KeyPairGenerator gen = java.security.KeyPairGenerator.getInstance("RSA");
    gen.initialize(2048);
    java.security.KeyPair pair = gen.generateKeyPair();
    String pem =
        "-----BEGIN PRIVATE KEY-----\n"
            + java.util.Base64.getMimeEncoder(
                    64, "\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                .encodeToString(pair.getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----\n";
    String saJson =
        "{\"client_email\":\"sa@medmate.iam.gserviceaccount.com\",\"private_key\":\""
            + pem.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
            + "\"}";
    assertThat(cfg.httpFcmClient("proj", saJson)).isNotNull();
    assertThat(cfg.httpTwilioClient("sid", "tok", "key")).isNotNull();
  }
}
