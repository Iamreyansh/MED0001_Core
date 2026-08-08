package com.nammamedmate.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class HaversineTest {

  @Test
  void samePointIsZero() {
    assertThat(Haversine.distanceKm(12.93, 77.61, 12.93, 77.61)).isEqualTo(0.0);
  }

  @Test
  void knownShortDistanceInBengaluru() {
    // ~1.1–1.3 km between two nearby Koramangala points
    double d = Haversine.distanceKm(12.9345, 77.6125, 12.9355, 77.6225);
    assertThat(d).isBetween(0.8, 1.5);
  }

  @Test
  void isSymmetric() {
    double a = Haversine.distanceKm(12.9, 77.6, 13.0, 77.7);
    double b = Haversine.distanceKm(13.0, 77.7, 12.9, 77.6);
    assertThat(a).isCloseTo(b, within(1e-9));
  }
}
