package com.nammamedmate.customer.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LoyaltyTiersTest {

  @Test
  void fromPoints_appliesThresholds() {
    assertThat(LoyaltyTiers.fromPoints(0)).isEqualTo("BRONZE");
    assertThat(LoyaltyTiers.fromPoints(49)).isEqualTo("BRONZE");
    assertThat(LoyaltyTiers.fromPoints(50)).isEqualTo("SILVER");
    assertThat(LoyaltyTiers.fromPoints(199)).isEqualTo("SILVER");
    assertThat(LoyaltyTiers.fromPoints(200)).isEqualTo("GOLD");
    assertThat(LoyaltyTiers.fromPoints(10_000)).isEqualTo("GOLD");
  }
}
