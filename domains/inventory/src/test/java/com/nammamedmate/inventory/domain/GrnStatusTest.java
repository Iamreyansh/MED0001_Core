package com.nammamedmate.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GrnStatusTest {

  @Test
  void editableFlags() {
    assertThat(GrnStatus.DRAFT.editable()).isTrue();
    assertThat(GrnStatus.SAVED.editable()).isTrue();
    assertThat(GrnStatus.STOCKED.editable()).isFalse();
  }
}
