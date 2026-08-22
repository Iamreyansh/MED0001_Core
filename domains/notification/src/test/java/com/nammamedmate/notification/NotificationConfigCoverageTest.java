package com.nammamedmate.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NotificationConfigCoverageTest {

  @Test
  void deployedHttpBeansRequireSecretsAndConstruct() {
    NotificationConfig cfg = new NotificationConfig();
    assertThat(cfg.httpFcmClient("fcm-key")).isNotNull();
    assertThat(cfg.httpMsg91Client("msg91-key")).isNotNull();
    assertThat(cfg.httpTwilioClient("sid", "tok")).isNotNull();
    assertThat(cfg.httpMetaWhatsAppClient("tok", "sec")).isNotNull();
    assertThat(cfg.httpSendGridClient("sg")).isNotNull();
    assertThat(cfg.httpSesClient("ap-south-1")).isNotNull();
    assertThat(cfg.stubFcmClient()).isNotNull();
    assertThat(cfg.stubMsg91Client()).isNotNull();
    assertThat(cfg.stubTwilioClient()).isNotNull();
    assertThat(cfg.stubSendGridClient()).isNotNull();
    assertThat(cfg.stubSesClient()).isNotNull();
  }
}
