package com.nammamedmate.auth.adapter.out.sms;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class Msg91OtpSmsSenderTest {

  @Test
  void requiresKeyAndSends() {
    assertThatThrownBy(() -> new Msg91OtpSmsSender(" ")).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new Msg91OtpSmsSender((String) null))
        .isInstanceOf(IllegalStateException.class);
    Msg91OtpSmsSender ok = new Msg91OtpSmsSender("key", (p, o, k) -> 204);
    ok.sendOtp("+919876543210", "123456");
    assertThatThrownBy(() -> new Msg91OtpSmsSender("key", (p, o, k) -> 500).sendOtp("+91", "1"))
        .isInstanceOf(IllegalStateException.class);
    new Msg91OtpSmsSender("live-key").sendOtp("+919876543210", "000000");
  }
}
