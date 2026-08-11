package com.nammamedmate.medicine_schedule.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.error.AppException;
import org.junit.jupiter.api.Test;

class MedicineScheduleInternalAuthTest {

  @Test
  void requireValidToken() {
    MedicineScheduleInternalAuth auth = new MedicineScheduleInternalAuth("secret");
    auth.require("secret");
  }

  @Test
  void rejectMissingConfigured() {
    MedicineScheduleInternalAuth auth = new MedicineScheduleInternalAuth("");
    assertThatThrownBy(() -> auth.require("x"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void rejectBadToken() {
    MedicineScheduleInternalAuth auth = new MedicineScheduleInternalAuth("secret");
    assertThatThrownBy(() -> auth.require("nope"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> auth.require(null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void nullConfigTreatedEmpty() {
    MedicineScheduleInternalAuth auth = new MedicineScheduleInternalAuth(null);
    assertThatThrownBy(() -> auth.require("a")).isInstanceOf(AppException.class);
  }
}
