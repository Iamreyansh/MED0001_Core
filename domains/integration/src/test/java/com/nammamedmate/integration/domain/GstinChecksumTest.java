package com.nammamedmate.integration.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class GstinChecksumTest {

  @Test
  void acceptsKnownValidGstins() {
    assertThat(GstinChecksum.isValid("27AAPFU0939F1ZV")).isTrue();
    assertThat(GstinChecksum.isValid("29ABCDE1234F1ZW")).isTrue();
    assertThat(GstinChecksum.isValid("29abcde1234f1zw")).isTrue();
  }

  @Test
  void rejectsBadFormatAndChecksum() {
    assertThat(GstinChecksum.isValid(null)).isFalse();
    assertThat(GstinChecksum.isValid("short")).isFalse();
    assertThat(GstinChecksum.isValid("29ABCDE1234F1Z0")).isFalse();
    assertThat(GstinChecksum.isValid("29ABCDE1234F1A0")).isFalse();
  }

  @Test
  void checkDigitHelpers() {
    assertThat(GstinChecksum.checkDigit("27AAPFU0939F1Z")).isEqualTo('V');
    assertThatThrownBy(() -> GstinChecksum.checkDigit("short"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> GstinChecksum.checkDigit("27AAPFU0939F1!"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
