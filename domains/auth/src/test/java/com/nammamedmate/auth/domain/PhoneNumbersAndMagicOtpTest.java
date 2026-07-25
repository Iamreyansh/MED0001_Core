package com.nammamedmate.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PhoneNumbersAndMagicOtpTest {

  @Test
  void validatesIndianMobiles() {
    assertThat(PhoneNumbers.isValidIndianMobile("+919876543210")).isTrue();
    assertThat(PhoneNumbers.isValidIndianMobile("+915876543210")).isFalse();
    assertThat(PhoneNumbers.isValidIndianMobile(null)).isFalse();
    assertThat(PhoneNumbers.isValidIndianMobile("9876543210")).isFalse();
  }

  @Test
  void magicOtpRange() {
    assertThat(MagicOtp.isTestPhone("+919999900000")).isTrue();
    assertThat(MagicOtp.isTestPhone("+919999900099")).isTrue();
    assertThat(MagicOtp.isTestPhone("+919999900100")).isFalse();
    assertThat(MagicOtp.isTestPhone(null)).isFalse();
    assertThat(MagicOtp.isTestPhone("919999900000")).isFalse();
    assertThat(MagicOtp.isTestPhone("+91notanumber")).isFalse();
    assertThat(MagicOtp.matches("+919999900001", "123456")).isTrue();
    assertThat(MagicOtp.matches("+919999900001", "000000")).isFalse();
    assertThat(MagicOtp.matches("+919876543210", "123456")).isFalse();
  }
}
