package com.nammamedmate.prescription.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Period;
import org.junit.jupiter.api.Test;

class ScheduleDrugRegisterEntryTest {

  @Test
  void retentionAndScheduleHelpers() {
    assertThat(ScheduleDrugRegisterEntry.retentionFor("H1")).isEqualTo(Period.ofYears(3));
    assertThat(ScheduleDrugRegisterEntry.retentionFor("X")).isEqualTo(Period.ofYears(5));
    assertThat(ScheduleDrugRegisterEntry.retentionFor("x")).isEqualTo(Period.ofYears(5));
    assertThat(ScheduleDrugRegisterEntry.isRegisterSchedule("H1")).isTrue();
    assertThat(ScheduleDrugRegisterEntry.isRegisterSchedule("X")).isTrue();
    assertThat(ScheduleDrugRegisterEntry.isRegisterSchedule("H")).isFalse();
  }
}
