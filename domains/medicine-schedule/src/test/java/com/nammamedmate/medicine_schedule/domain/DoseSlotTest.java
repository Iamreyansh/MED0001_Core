package com.nammamedmate.medicine_schedule.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DoseSlotTest {

  @Test
  void acceptsValidSlotAndTime() {
    DoseSlot slot = new DoseSlot("morning", "08:00");
    assertThat(slot.slot()).isEqualTo("MORNING");
    assertThat(slot.reminderTime()).isEqualTo("08:00");
  }

  @Test
  void rejectsInvalidTime() {
    assertThatThrownBy(() -> new DoseSlot("MORNING", "8:00"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("INVALID_REMINDER_TIME");
    assertThatThrownBy(() -> new DoseSlot("MORNING", null)).hasMessage("INVALID_REMINDER_TIME");
  }

  @Test
  void rejectsInvalidSlot() {
    assertThatThrownBy(() -> new DoseSlot("DAWN", "08:00"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
