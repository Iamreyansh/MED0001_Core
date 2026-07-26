package com.nammamedmate.customer.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CustomerGenderTest {

  @Test
  void parse_acceptsAllValues() {
    assertThat(CustomerGender.parse("male")).isEqualTo(CustomerGender.MALE);
    assertThat(CustomerGender.parse("FEMALE")).isEqualTo(CustomerGender.FEMALE);
    assertThat(CustomerGender.parse(" other ")).isEqualTo(CustomerGender.OTHER);
    assertThat(CustomerGender.parse("prefer_not_to_say"))
        .isEqualTo(CustomerGender.PREFER_NOT_TO_SAY);
  }

  @Test
  void parse_nullOrBlank_returnsNull() {
    assertThat(CustomerGender.parse(null)).isNull();
    assertThat(CustomerGender.parse("  ")).isNull();
  }

  @Test
  void parse_invalid_throws() {
    assertThatThrownBy(() -> CustomerGender.parse("robot"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid gender");
  }
}
