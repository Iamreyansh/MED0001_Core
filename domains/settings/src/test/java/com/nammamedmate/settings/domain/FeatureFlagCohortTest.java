package com.nammamedmate.settings.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FeatureFlagCohortTest {

  private static final UUID USER = UUID.fromString("11111111-1111-4111-8111-111111111111");

  @Test
  void killSwitchAndBounds() {
    assertThat(FeatureFlagCohort.isEnabledForUser(false, 100, USER, "cod_enabled")).isFalse();
    assertThat(FeatureFlagCohort.isEnabledForUser(true, 0, USER, "cod_enabled")).isFalse();
    assertThat(FeatureFlagCohort.isEnabledForUser(true, 100, USER, "cod_enabled")).isTrue();
    assertThat(FeatureFlagCohort.isEnabledForUser(true, 50, null, "cod_enabled")).isFalse();
    assertThat(FeatureFlagCohort.isEnabledForUser(true, 50, USER, null)).isFalse();
  }

  @Test
  void deterministicBucketAndCohort() {
    int bucket = FeatureFlagCohort.bucket(USER, "new_checkout_flow");
    assertThat(bucket).isBetween(0, 99);
    assertThat(FeatureFlagCohort.bucket(USER, "new_checkout_flow")).isEqualTo(bucket);
    assertThat(FeatureFlagCohort.bucket(null, "x")).isZero();
    assertThat(FeatureFlagCohort.bucket(USER, null)).isZero();

    boolean in = FeatureFlagCohort.isEnabledForUser(true, bucket + 1, USER, "new_checkout_flow");
    boolean out = FeatureFlagCohort.isEnabledForUser(true, bucket, USER, "new_checkout_flow");
    assertThat(in).isTrue();
    assertThat(out).isFalse();
  }

  @Test
  void digestUnavailable() {
    assertThatThrownBy(
            () ->
                FeatureFlagCohort.bucket(
                    USER,
                    "x",
                    () -> {
                      throw new NoSuchAlgorithmException("boom");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SHA-256");
  }
}
