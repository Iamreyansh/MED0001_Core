package com.nammamedmate.pharmacy.adapter.out.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.nammamedmate.kernel.id.Ids;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class StubPharmacyOrderMetricsClientExtendedTest {

  @Test
  void annualAndPeriodGmvReturnZero() {
    StubPharmacyOrderMetricsClient client = new StubPharmacyOrderMetricsClient();
    assertThat(client.annualGmvYtdPaise(Ids.newId())).isZero();
    assertThat(client.gmvForPeriodPaise(Ids.newId(), LocalDate.now(), LocalDate.now())).isZero();
  }
}
