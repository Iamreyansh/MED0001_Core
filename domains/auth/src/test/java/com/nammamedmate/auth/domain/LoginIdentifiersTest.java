package com.nammamedmate.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LoginIdentifiersTest {

  @Test
  void emailNormalisedToLowercase() {
    var result = LoginIdentifiers.normalise("Priya@SriRama.IN");
    assertThat(result).isNotNull();
    assertThat(result.value()).isEqualTo("priya@srirama.in");
    assertThat(result.type()).isEqualTo(LoginIdentifiers.Type.EMAIL);
  }

  @Test
  void phoneStripsSpaces() {
    var result = LoginIdentifiers.normalise("+91 98765 43210");
    assertThat(result).isNotNull();
    assertThat(result.value()).isEqualTo("+919876543210");
    assertThat(result.type()).isEqualTo(LoginIdentifiers.Type.PHONE);
  }

  @Test
  void nullOrBlankReturnsNull() {
    assertThat(LoginIdentifiers.normalise(null)).isNull();
    assertThat(LoginIdentifiers.normalise("")).isNull();
    assertThat(LoginIdentifiers.normalise("   ")).isNull();
  }

  @Test
  void plainPhoneRetainedAsPhone() {
    var result = LoginIdentifiers.normalise("+919876543210");
    assertThat(result.type()).isEqualTo(LoginIdentifiers.Type.PHONE);
    assertThat(result.value()).isEqualTo("+919876543210");
  }

  @Test
  void rejectsMalformedEmailAndPhone() {
    assertThatThrownBy(() -> LoginIdentifiers.normalise("not-a-phone"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> LoginIdentifiers.normalise("bad@"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> LoginIdentifiers.normalise("+911234567890"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
