package com.nammamedmate.catalogue.adapter.out.medicine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class StubPortsTest {

  @Test
  void stubsReturnZero() {
    UUID id = UUID.randomUUID();
    assertThat(new StubBanMappingHideClient().hideAllForMedicine(id)).isZero();
    assertThat(new StubOrderDemandClient().trailing30DayOrderCount(id)).isZero();
  }
}
