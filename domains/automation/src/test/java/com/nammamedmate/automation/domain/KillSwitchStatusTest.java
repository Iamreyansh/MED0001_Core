package com.nammamedmate.automation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KillSwitchStatusTest {

  @Test
  void fromParses() {
    assertThat(KillSwitchStatus.from(null)).isEqualTo(KillSwitchStatus.ACTIVE);
    assertThat(KillSwitchStatus.from(" ")).isEqualTo(KillSwitchStatus.ACTIVE);
    assertThat(KillSwitchStatus.from("paused")).isEqualTo(KillSwitchStatus.PAUSED);
    assertThat(KillSwitchStatus.from("ACTIVE")).isEqualTo(KillSwitchStatus.ACTIVE);
  }
}
