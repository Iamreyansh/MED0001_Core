package com.nammamedmate.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CrmPaymentBridgeConfigTest {

  @Test
  void constructs() {
    assertThat(new CrmPaymentBridgeConfig()).isNotNull();
  }
}
