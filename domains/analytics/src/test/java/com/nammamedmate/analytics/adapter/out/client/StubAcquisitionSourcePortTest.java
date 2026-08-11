package com.nammamedmate.analytics.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.nammamedmate.analytics.application.port.out.AcquisitionSourcePort.Source;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StubAcquisitionSourcePortTest {

  @Test
  void alwaysOrganic() {
    assertThat(new StubAcquisitionSourcePort().sourceForCustomer(UUID.randomUUID()))
        .isEqualTo(Source.ORGANIC);
  }
}
