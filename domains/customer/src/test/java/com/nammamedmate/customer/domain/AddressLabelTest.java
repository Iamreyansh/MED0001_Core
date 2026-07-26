package com.nammamedmate.customer.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AddressLabelTest {

  @Test
  void parse_acceptsValues() {
    assertThat(AddressLabel.parse("home")).isEqualTo(AddressLabel.HOME);
    assertThat(AddressLabel.parse(" WORK ")).isEqualTo(AddressLabel.WORK);
    assertThat(AddressLabel.parse("OTHER")).isEqualTo(AddressLabel.OTHER);
  }

  @Test
  void parse_nullOrBlank_throws() {
    assertThatThrownBy(() -> AddressLabel.parse(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("label is required");
    assertThatThrownBy(() -> AddressLabel.parse(" "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("label is required");
  }

  @Test
  void parse_invalid_throws() {
    assertThatThrownBy(() -> AddressLabel.parse("SCHOOL"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("HOME, WORK, OTHER");
  }
}
