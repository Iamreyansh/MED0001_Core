package com.nammamedmate.customer.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.error.AppException;
import org.junit.jupiter.api.Test;

class LoyaltyTxTypeTest {

  @Test
  void parseOptional() {
    assertThat(LoyaltyTxType.parseOptional(null)).isNull();
    assertThat(LoyaltyTxType.parseOptional(" ")).isNull();
    assertThat(LoyaltyTxType.parseOptional("earn")).isEqualTo(LoyaltyTxType.EARN);
    assertThatThrownBy(() -> LoyaltyTxType.parseOptional("NOPE"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }
}
