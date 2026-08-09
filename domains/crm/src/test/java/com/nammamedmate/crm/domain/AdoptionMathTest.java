package com.nammamedmate.crm.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdoptionMathTest {

  @Test
  void adoptionPctAndLowFlag() {
    assertThat(AdoptionMath.adoptionPct(540, 600)).isEqualTo(90.0);
    assertThat(AdoptionMath.adoptionPct(28, 180)).isEqualTo(15.6);
    assertThat(AdoptionMath.adoptionPct(0, 0)).isEqualTo(0.0);
    assertThat(AdoptionMath.isLowAdoption(15.6)).isTrue();
    assertThat(AdoptionMath.isLowAdoption(20.0)).isFalse();
  }

  @Test
  void adoptionScoreFloor() {
    assertThat(AdoptionMath.adoptionScore(5, 8)).isEqualTo(62);
    assertThat(AdoptionMath.adoptionScore(0, 0)).isEqualTo(0);
  }
}
