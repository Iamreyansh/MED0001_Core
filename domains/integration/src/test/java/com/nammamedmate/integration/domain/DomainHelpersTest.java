package com.nammamedmate.integration.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DomainHelpersTest {

  @Test
  void payoutModesAutoSelect() {
    assertThat(PayoutModes.autoSelect(20_000_000L)).isEqualTo(PayoutModes.IMPS);
    assertThat(PayoutModes.autoSelect(20_000_001L)).isEqualTo(PayoutModes.NEFT);
  }

  @Test
  void entityTypes() {
    assertThat(EntityTypes.isValid("PHARMACY")).isTrue();
    assertThat(EntityTypes.isValid("RIDER")).isTrue();
    assertThat(EntityTypes.isValid("OTHER")).isFalse();
  }

  @Test
  void statusConstantsReachable() {
    assertThat(PaymentStatuses.CREATED).isEqualTo("created");
    assertThat(PayoutStatuses.FAILED).isEqualTo("failed");
    assertThat(PayoutModes.UPI).isEqualTo("UPI");
    assertThat(MapsApiTypes.GEOCODE).isEqualTo("GEOCODE");
    assertThat(MapsApiTypes.ZONE_CHECK).isEqualTo("ZONE_CHECK");
  }

  @Test
  void pointInPolygonRayCasting() {
    double[][] square = {{0, 0}, {0, 10}, {10, 10}, {10, 0}, {0, 0}};
    assertThat(PointInPolygon.contains(5, 5, square)).isTrue();
    assertThat(PointInPolygon.contains(20, 20, square)).isFalse();
    assertThat(PointInPolygon.contains(1, 1, null)).isFalse();
    assertThat(PointInPolygon.contains(1, 1, new double[][] {{0, 0}, {1, 1}})).isFalse();
  }
}
