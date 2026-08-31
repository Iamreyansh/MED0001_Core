package com.nammamedmate.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.NoSuchAlgorithmException;
import org.junit.jupiter.api.Test;

class PharmacyStaffTokensTest {

  @Test
  void generateAndHashAreStable() {
    String token = PharmacyStaffTokens.generate();
    assertThat(token).isNotBlank();
    assertThat(PharmacyStaffTokens.sha256Hex(token)).hasSize(64);
    assertThat(PharmacyStaffTokens.sha256Hex(token))
        .isEqualTo(PharmacyStaffTokens.sha256Hex(token));
  }

  @Test
  void digestFailureWraps() {
    assertThatThrownBy(
            () ->
                PharmacyStaffTokens.sha256Hex(
                    "x",
                    () -> {
                      throw new NoSuchAlgorithmException("no");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SHA-256");
  }
}
