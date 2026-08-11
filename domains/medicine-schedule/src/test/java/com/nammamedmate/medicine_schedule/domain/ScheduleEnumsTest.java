package com.nammamedmate.medicine_schedule.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ScheduleEnumsTest {

  @Test
  void medicineForm() {
    assertThat(MedicineForm.parse("tablet")).isEqualTo(MedicineForm.TABLET);
    assertThatThrownBy(() -> MedicineForm.parse(null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> MedicineForm.parse(" ")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> MedicineForm.parse("PILL"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void foodInstruction() {
    assertThat(FoodInstruction.parse("after")).isEqualTo(FoodInstruction.AFTER);
    assertThatThrownBy(() -> FoodInstruction.parse(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> FoodInstruction.parse(" "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> FoodInstruction.parse("SOMETIMES"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid food_instruction");
  }

  @Test
  void durationType() {
    assertThat(DurationType.parse("DAYS")).isEqualTo(DurationType.DAYS);
    assertThatThrownBy(() -> DurationType.parse(null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> DurationType.parse("")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> DurationType.parse("WEEKLY"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void doseSlotName() {
    assertThat(DoseSlotName.parse("night")).isEqualTo(DoseSlotName.NIGHT);
    assertThatThrownBy(() -> DoseSlotName.parse(null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> DoseSlotName.parse("")).isInstanceOf(IllegalArgumentException.class);
  }
}
