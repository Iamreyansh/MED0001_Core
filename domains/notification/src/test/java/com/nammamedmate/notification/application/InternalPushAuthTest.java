package com.nammamedmate.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.error.AppException;
import org.junit.jupiter.api.Test;

class InternalPushAuthTest {

  @Test
  void rejectsMissingConfigAndBadToken() {
    assertThatThrownBy(() -> new InternalPushAuth("").require("x"))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> new InternalPushAuth(null).require("x"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> new InternalPushAuth("secret").require("wrong"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> new InternalPushAuth("secret").require(null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void acceptsMatchingToken() {
    new InternalPushAuth("secret").require("secret");
    assertThat(true).isTrue();
  }
}
