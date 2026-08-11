package com.nammamedmate.medicine_schedule.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CareCircleRelationshipTest {

  @Test
  void parseFamily_ok() {
    assertThat(CareCircleRelationship.parseFamily(" spouse "))
        .isEqualTo(CareCircleRelationship.SPOUSE);
  }

  @Test
  void parseFamily_rejectsSelfAndBlank() {
    assertThatThrownBy(() -> CareCircleRelationship.parseFamily("SELF"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CareCircleRelationship.parseFamily(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CareCircleRelationship.parseFamily(" "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CareCircleRelationship.parseFamily("COUSIN"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
