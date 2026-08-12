package com.nammamedmate.observability_ops.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AlertListStatusTest {

  @Test
  void defaultsAndParses() {
    assertThat(AlertListStatus.from(null)).isEqualTo(AlertListStatus.ACTIVE);
    assertThat(AlertListStatus.from("  ")).isEqualTo(AlertListStatus.ACTIVE);
    assertThat(AlertListStatus.from("resolved")).isEqualTo(AlertListStatus.RESOLVED);
  }
}
