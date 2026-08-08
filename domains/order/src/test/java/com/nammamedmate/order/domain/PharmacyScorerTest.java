package com.nammamedmate.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class PharmacyScorerTest {

  private static final UUID ID = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");

  @Test
  void multiplicativeWeightsAndComponents() {
    // distance 0 → distance_score 1; fill 100 → 1; rating 5 → 1; eta from 0km + 10 prep = 10
    // eta_score = 1 - 10/60 = 5/6
    PharmacyScore score = PharmacyScorer.score(ID, 0, 5, 100, 5, null);
    assertThat(score.distanceScore()).isEqualTo(1.0);
    assertThat(score.fillRateScore()).isEqualTo(1.0);
    assertThat(score.ratingScore()).isEqualTo(1.0);
    assertThat(score.deliveryEtaMinutes()).isEqualTo(10);
    assertThat(score.etaScore()).isCloseTo(1.0 - 10.0 / 60.0, within(1e-9));
    double expected = 0.60 * 1.0 + 0.20 * 1.0 + 0.10 * 1.0 + 0.10 * (1.0 - 10.0 / 60.0);
    assertThat(score.totalScore()).isCloseTo(expected, within(1e-9));
  }

  @Test
  void distanceAndEtaScoresDecreaseWithDistance() {
    PharmacyScore near = PharmacyScorer.score(ID, 1.0, 5.0, 80, 4.0, 10.0);
    PharmacyScore far = PharmacyScorer.score(ID, 4.0, 5.0, 80, 4.0, 10.0);
    assertThat(near.distanceScore()).isGreaterThan(far.distanceScore());
    assertThat(near.totalScore()).isGreaterThan(far.totalScore());
    assertThat(near.distanceScore()).isCloseTo(1.0 - 1.0 / 5.0, within(1e-9));
  }

  @Test
  void defaultPrepWhenMissingOrZero() {
    assertThat(PharmacyScorer.deliveryEtaMinutes(25.0, null)).isEqualTo(70); // 60 + 10
    assertThat(PharmacyScorer.deliveryEtaMinutes(25.0, 0.0)).isEqualTo(70);
    assertThat(PharmacyScorer.deliveryEtaMinutes(25.0, 15.0)).isEqualTo(75);
  }

  @Test
  void etaScoreFlooredAtZero() {
    // long distance → eta >> 60
    PharmacyScore score = PharmacyScorer.score(ID, 50, 5, 50, 3, 10.0);
    assertThat(score.etaScore()).isEqualTo(0.0);
  }

  @Test
  void clampsOutOfRangeComponents() {
    PharmacyScore high = PharmacyScorer.score(ID, -1, 5, 150, 10, 10.0);
    assertThat(high.distanceScore()).isEqualTo(1.0);
    assertThat(high.fillRateScore()).isEqualTo(1.0);
    assertThat(high.ratingScore()).isEqualTo(1.0);

    PharmacyScore low = PharmacyScorer.score(ID, 10, 5, -10, -1, 10.0);
    assertThat(low.distanceScore()).isEqualTo(0.0);
    assertThat(low.fillRateScore()).isEqualTo(0.0);
    assertThat(low.ratingScore()).isEqualTo(0.0);
  }

  @Test
  void zeroRadiusSafe() {
    PharmacyScore score = PharmacyScorer.score(ID, 1, 0, 50, 2.5, 10.0);
    assertThat(score.distanceScore()).isEqualTo(0.0);
  }
}
