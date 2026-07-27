package com.nammamedmate.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PharmacyConfigTest {

  @Test
  void providesStubVerificationPorts() {
    PharmacyConfig config = new PharmacyConfig();
    assertThat(config.gstinVerificationPort()).isNotNull();
    assertThat(config.drugLicenceVerificationPort()).isNotNull();
    assertThat(config.fssaiVerificationPort()).isNotNull();
  }
}
