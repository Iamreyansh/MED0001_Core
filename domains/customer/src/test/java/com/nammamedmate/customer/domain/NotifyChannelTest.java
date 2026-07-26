package com.nammamedmate.customer.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class NotifyChannelTest {

  @Test
  void parse_acceptsAllValues() {
    assertThat(NotifyChannel.parse("push")).isEqualTo(NotifyChannel.PUSH);
    assertThat(NotifyChannel.parse("SMS")).isEqualTo(NotifyChannel.SMS);
    assertThat(NotifyChannel.parse(" both ")).isEqualTo(NotifyChannel.BOTH);
  }

  @Test
  void parse_nullOrBlank_throws() {
    assertThatThrownBy(() -> NotifyChannel.parse(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("channel is required");
    assertThatThrownBy(() -> NotifyChannel.parse(""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("channel is required");
  }

  @Test
  void parse_invalid_throws() {
    assertThatThrownBy(() -> NotifyChannel.parse("email"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid channel");
  }
}
