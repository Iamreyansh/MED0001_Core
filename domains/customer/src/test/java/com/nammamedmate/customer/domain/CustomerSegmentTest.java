package com.nammamedmate.customer.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CustomerSegmentTest {

  @Test
  void parse_acceptsAllValues() {
    assertThat(CustomerSegment.parse("new")).isEqualTo(CustomerSegment.NEW);
    assertThat(CustomerSegment.parse("REGULAR")).isEqualTo(CustomerSegment.REGULAR);
    assertThat(CustomerSegment.parse(" loyal ")).isEqualTo(CustomerSegment.LOYAL);
    assertThat(CustomerSegment.parse("vip")).isEqualTo(CustomerSegment.VIP);
  }

  @Test
  void parse_nullOrBlank_returnsNull() {
    assertThat(CustomerSegment.parse(null)).isNull();
    assertThat(CustomerSegment.parse("")).isNull();
  }

  @Test
  void parse_invalid_throws() {
    assertThatThrownBy(() -> CustomerSegment.parse("platinum"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid segment");
  }

  @Test
  void compute_appliesOrderAndLtvThresholds() {
    assertThat(CustomerSegment.compute(0, 0)).isEqualTo(CustomerSegment.NEW);
    assertThat(CustomerSegment.compute(1, 0)).isEqualTo(CustomerSegment.REGULAR);
    assertThat(CustomerSegment.compute(11, 499_999L)).isEqualTo(CustomerSegment.REGULAR);
    assertThat(CustomerSegment.compute(12, 0)).isEqualTo(CustomerSegment.LOYAL);
    assertThat(CustomerSegment.compute(0, 500_000L)).isEqualTo(CustomerSegment.LOYAL);
    assertThat(CustomerSegment.compute(49, 2_499_999L)).isEqualTo(CustomerSegment.LOYAL);
    assertThat(CustomerSegment.compute(50, 0)).isEqualTo(CustomerSegment.VIP);
    assertThat(CustomerSegment.compute(0, 2_500_000L)).isEqualTo(CustomerSegment.VIP);
  }
}
