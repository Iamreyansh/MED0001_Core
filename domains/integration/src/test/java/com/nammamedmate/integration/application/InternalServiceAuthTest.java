package com.nammamedmate.integration.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.error.AppException;
import org.junit.jupiter.api.Test;

class InternalServiceAuthTest {

  @Test
  void blankConfiguredTokenRejects() {
    InternalServiceAuth auth = new InternalServiceAuth("");
    assertThatThrownBy(() -> auth.require("x"))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> new InternalServiceAuth(null).require("x"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void mismatchRejects() {
    InternalServiceAuth auth = new InternalServiceAuth("secret");
    assertThatThrownBy(() -> auth.require("other"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> auth.require(null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void matchAccepts() {
    assertThatCode(() -> new InternalServiceAuth("secret").require("secret"))
        .doesNotThrowAnyException();
  }
}
