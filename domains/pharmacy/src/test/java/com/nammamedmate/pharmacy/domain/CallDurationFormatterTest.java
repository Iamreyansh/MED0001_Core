package com.nammamedmate.pharmacy.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CallDurationFormatterTest {

  @Test
  void formatsSecondsMinutesAndCombined() {
    assertThat(CallDurationFormatter.format(45)).isEqualTo("45s");
    assertThat(CallDurationFormatter.format(60)).isEqualTo("1m");
    assertThat(CallDurationFormatter.format(342)).isEqualTo("5m 42s");
  }
}
