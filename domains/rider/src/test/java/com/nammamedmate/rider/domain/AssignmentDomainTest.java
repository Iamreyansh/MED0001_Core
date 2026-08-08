package com.nammamedmate.rider.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class AssignmentDomainTest {

  @Test
  void otpGenerateAndHashRoundTrip() {
    String otp = AssignmentOtps.generate();
    assertThat(otp).hasSize(4);
    assertThat(AssignmentOtps.matches(otp, AssignmentOtps.hash(otp))).isTrue();
    assertThat(AssignmentOtps.matches("0000", AssignmentOtps.hash(otp))).isFalse();
    assertThat(AssignmentOtps.matches(null, "x")).isFalse();
    assertThat(AssignmentOtps.matches("1", null)).isFalse();
    assertThat(AssignmentOtps.hash(null)).isEmpty();
  }

  @Test
  void scoringWeightsPreferCloserHigherRatedLowerLoad() {
    BigDecimal closeHigh =
        AssignmentScoring.composite(0.5, BigDecimal.valueOf(5.0), 0, BigDecimal.valueOf(100));
    BigDecimal farLow =
        AssignmentScoring.composite(9.0, BigDecimal.valueOf(2.0), 2, BigDecimal.valueOf(40));
    assertThat(closeHigh).isGreaterThan(farLow);
    BigDecimal nulls = AssignmentScoring.composite(1.0, null, 0, null);
    assertThat(nulls).isPositive();
  }
}
