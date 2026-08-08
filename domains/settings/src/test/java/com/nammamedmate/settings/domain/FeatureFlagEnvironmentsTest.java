package com.nammamedmate.settings.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FeatureFlagEnvironmentsTest {

  @Test
  void validatesKnownEnvironments() {
    assertThat(FeatureFlagEnvironments.isValid("production")).isTrue();
    assertThat(FeatureFlagEnvironments.isValid("staging")).isTrue();
    assertThat(FeatureFlagEnvironments.isValid("development")).isTrue();
    assertThat(FeatureFlagEnvironments.isValid("prod")).isFalse();
    assertThat(FeatureFlagEnvironments.isValid(null)).isFalse();
  }
}
