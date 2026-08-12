package com.nammamedmate.automation.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.error.AppException;
import org.junit.jupiter.api.Test;

class InternalAutomationAuthTest {

  @Test
  void requireValidatesToken() {
    InternalAutomationAuth auth = new InternalAutomationAuth("secret");
    auth.require("secret");
    assertThatThrownBy(() -> auth.require("bad")).isInstanceOf(AppException.class);
    assertThatThrownBy(() -> auth.require(null)).isInstanceOf(AppException.class);
    assertThatThrownBy(() -> new InternalAutomationAuth(" ").require("x"))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(() -> new InternalAutomationAuth(null).require("x"))
        .isInstanceOf(AppException.class);
  }
}
