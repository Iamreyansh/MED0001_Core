package com.nammamedmate.settings.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.NoSuchAlgorithmException;
import org.junit.jupiter.api.Test;

class StaffTokensTest {

  @Test
  void generateAndHash() throws Exception {
    String token = StaffTokens.generate();
    assertThat(token).isNotBlank();
    String hash = StaffTokens.sha256Hex(token);
    assertThat(hash).hasSize(64);
    assertThat(StaffTokens.sha256Hex(token)).isEqualTo(hash);
    assertThat(StaffTokens.sha256Hex("other")).isNotEqualTo(hash);
    var ctor = StaffTokens.class.getDeclaredConstructor();
    ctor.setAccessible(true);
    assertThat(ctor.newInstance()).isNotNull();
  }

  @Test
  void sha256Unavailable() {
    assertThatThrownBy(
            () ->
                StaffTokens.sha256Hex(
                    "x",
                    () -> {
                      throw new NoSuchAlgorithmException("boom");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SHA-256");
  }
}
