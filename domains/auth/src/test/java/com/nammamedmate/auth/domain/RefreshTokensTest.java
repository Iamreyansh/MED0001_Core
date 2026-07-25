package com.nammamedmate.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Constructor;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

class RefreshTokensTest {

  @Test
  void generateAndHashAreStableShapes() {
    String token = RefreshTokens.generate(new SecureRandom());
    assertThat(token).isNotBlank();
    String hash = RefreshTokens.sha256Hex(token);
    assertThat(hash).hasSize(64);
    assertThat(RefreshTokens.sha256Hex(token)).isEqualTo(hash);
  }

  @Test
  void sha256FailsWhenDigestUnavailable() {
    assertThatThrownBy(
            () ->
                RefreshTokens.sha256Hex(
                    "x",
                    () -> {
                      throw new NoSuchAlgorithmException("boom");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SHA-256");
  }

  @Test
  void privateConstructorForCoverage() throws Exception {
    Constructor<RefreshTokens> ctor = RefreshTokens.class.getDeclaredConstructor();
    ctor.setAccessible(true);
    assertThat(ctor.newInstance()).isNotNull();
  }
}
