package com.nammamedmate.payment.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.error.AppException;
import org.junit.jupiter.api.Test;

class InternalWalletTokenAuthTest {

  @Test
  void blankConfigRejects() {
    InternalWalletTokenAuth auth = new InternalWalletTokenAuth("");
    assertThatThrownBy(() -> auth.require("anything"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> new InternalWalletTokenAuth(null).require("x"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void mismatchRejects() {
    InternalWalletTokenAuth auth = new InternalWalletTokenAuth("secret");
    assertThatThrownBy(() -> auth.require("wrong"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> auth.require(null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void matchAccepts() {
    new InternalWalletTokenAuth("secret").require("secret");
  }
}
