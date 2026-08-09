package com.nammamedmate.payment.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class TaxStorePortRecordsTest {

  @Test
  void nullCollectionsBecomeEmpty() {
    assertThat(
            new TaxStorePort.TcsRegisterRecord(
                    null, null, "2026-07", "", "", "", 0, 0, 0, 0, null, null)
                .settlementIds())
        .isEmpty();
    assertThat(new TaxStorePort.TcsPage(null, 0).entries()).isEmpty();
    assertThat(new TaxStorePort.TcsPage(List.of(), 1).total()).isEqualTo(1);
  }
}
