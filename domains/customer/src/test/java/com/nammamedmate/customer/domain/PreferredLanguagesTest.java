package com.nammamedmate.customer.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PreferredLanguagesTest {

  @Test
  void isAllowed_acceptsSupportedCodes() {
    assertThat(PreferredLanguages.ALLOWED)
        .containsExactlyInAnyOrder("en", "kn", "hi", "ta", "te", "ml", "mr");
    for (String code : PreferredLanguages.ALLOWED) {
      assertThat(PreferredLanguages.isAllowed(code)).isTrue();
      assertThat(PreferredLanguages.isAllowed(code.toUpperCase())).isTrue();
    }
  }

  @Test
  void isAllowed_rejectsUnsupportedAndNull() {
    assertThat(PreferredLanguages.isAllowed("de")).isFalse();
    assertThat(PreferredLanguages.isAllowed(null)).isFalse();
  }

  @Test
  void normalize_lowercasesCode() {
    assertThat(PreferredLanguages.normalize("EN")).isEqualTo("en");
  }
}
