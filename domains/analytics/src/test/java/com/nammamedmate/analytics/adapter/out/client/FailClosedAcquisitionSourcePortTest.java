package com.nammamedmate.analytics.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class FailClosedAcquisitionSourcePortTest {

  @Test
  void sourceFailsClosed() {
    assertThatThrownBy(
            () -> new FailClosedAcquisitionSourcePort().sourceForCustomer(UUID.randomUUID()))
        .hasMessageContaining("Acquisition attribution is not configured");
  }
}
