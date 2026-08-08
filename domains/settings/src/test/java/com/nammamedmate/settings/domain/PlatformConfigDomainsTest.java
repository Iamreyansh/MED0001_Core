package com.nammamedmate.settings.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PlatformConfigDomainsTest {

  @Test
  void validatesKnownDomains() {
    assertThat(PlatformConfigDomains.isValid("orders")).isTrue();
    assertThat(PlatformConfigDomains.isValid("payments")).isTrue();
    assertThat(PlatformConfigDomains.isValid("commissions")).isTrue();
    assertThat(PlatformConfigDomains.isValid("kyc")).isTrue();
    assertThat(PlatformConfigDomains.isValid("rider")).isTrue();
    assertThat(PlatformConfigDomains.isValid("unknown")).isFalse();
    assertThat(PlatformConfigDomains.isValid(null)).isFalse();
    assertThat(PlatformConfigDomains.ALL).hasSize(5);
  }
}
