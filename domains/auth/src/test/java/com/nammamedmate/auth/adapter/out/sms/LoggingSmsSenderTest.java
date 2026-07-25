package com.nammamedmate.auth.adapter.out.sms;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LoggingSmsSenderTest {

  @Test
  void sendOtpDoesNotThrow() {
    new LoggingSmsSender().sendOtp("+919876543210", "123456");
    new LoggingSmsSender().sendOtp("+919876543210", null);
  }

  @Test
  void rejectsNullPhone() {
    assertThatThrownBy(() -> new LoggingSmsSender().sendOtp(null, "123456"))
        .isInstanceOf(NullPointerException.class);
  }
}
