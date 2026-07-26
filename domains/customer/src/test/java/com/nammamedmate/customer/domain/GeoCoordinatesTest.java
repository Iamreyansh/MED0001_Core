package com.nammamedmate.customer.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class GeoCoordinatesTest {

  @Test
  void requireLatitude_acceptsRange() {
    assertThat(GeoCoordinates.requireLatitude(-90.0)).isEqualTo(-90.0);
    assertThat(GeoCoordinates.requireLatitude(90.0)).isEqualTo(90.0);
    assertThat(GeoCoordinates.requireLatitude(12.9716)).isEqualTo(12.9716);
  }

  @Test
  void requireLatitude_rejectsInvalid() {
    assertThatThrownBy(() -> GeoCoordinates.requireLatitude(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> GeoCoordinates.requireLatitude(-90.1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> GeoCoordinates.requireLatitude(90.1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void requireLongitude_acceptsRange() {
    assertThat(GeoCoordinates.requireLongitude(-180.0)).isEqualTo(-180.0);
    assertThat(GeoCoordinates.requireLongitude(180.0)).isEqualTo(180.0);
  }

  @Test
  void requireLongitude_rejectsInvalid() {
    assertThatThrownBy(() -> GeoCoordinates.requireLongitude(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> GeoCoordinates.requireLongitude(-180.1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> GeoCoordinates.requireLongitude(180.1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
