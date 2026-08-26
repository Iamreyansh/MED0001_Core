package com.nammamedmate.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.nammamedmate.messaging.OutboxPublisher;
import org.junit.jupiter.api.Test;

class PharmacyConfigTest {

  @Test
  void providesStubPorts() {
    PharmacyConfig config = new PharmacyConfig();
    assertThat(config.pennyDropPort()).isNotNull();
    assertThat(config.pharmacyOrderMetricsPort()).isNotNull();
    assertThat(config.notificationDispatchPort(mock(OutboxPublisher.class))).isNotNull();
    assertThat(config.cashfreeXPayoutPort()).isNotNull();
  }
}
