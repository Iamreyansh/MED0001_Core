package com.nammamedmate.customer.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class FlagReasonTest {

  @Test
  void parse_acceptsAllValues() {
    assertThat(FlagReason.parse("high_cancellation")).isEqualTo(FlagReason.HIGH_CANCELLATION);
    assertThat(FlagReason.parse("FRAUD_SUSPICION")).isEqualTo(FlagReason.FRAUD_SUSPICION);
    assertThat(FlagReason.parse(" abusive_behaviour ")).isEqualTo(FlagReason.ABUSIVE_BEHAVIOUR);
    assertThat(FlagReason.parse("duplicate_account")).isEqualTo(FlagReason.DUPLICATE_ACCOUNT);
    assertThat(FlagReason.parse("payment_default")).isEqualTo(FlagReason.PAYMENT_DEFAULT);
    assertThat(FlagReason.parse("other")).isEqualTo(FlagReason.OTHER);
  }

  @Test
  void parse_nullOrBlank_throws() {
    assertThatThrownBy(() -> FlagReason.parse(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("reason is required");
    assertThatThrownBy(() -> FlagReason.parse(" "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("reason is required");
  }

  @Test
  void parse_invalid_throws() {
    assertThatThrownBy(() -> FlagReason.parse("spam"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid flag reason");
  }
}
