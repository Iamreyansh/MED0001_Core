package com.nammamedmate.medicine_schedule.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DoseLogStatusTest {

  @Test
  void parseMarkStatus() {
    assertThat(DoseLogStatus.parseMarkStatus("taken")).isEqualTo(DoseLogStatus.TAKEN);
    assertThat(DoseLogStatus.parseMarkStatus("SKIPPED")).isEqualTo(DoseLogStatus.SKIPPED);
  }

  @Test
  void invalid() {
    assertThatThrownBy(() -> DoseLogStatus.parseMarkStatus("MISSED"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> DoseLogStatus.parseMarkStatus(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> DoseLogStatus.parseMarkStatus(" "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void enumsExist() {
    assertThat(ReminderStatus.SCHEDULED.name()).isEqualTo("SCHEDULED");
    assertThat(ReminderChannel.PUSH.name()).isEqualTo("PUSH");
  }
}
