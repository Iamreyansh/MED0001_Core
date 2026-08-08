package com.nammamedmate.settings.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AuditActorTypesTest {

  @Test
  void validateAndNormalize() {
    assertThat(AuditActorTypes.isValid("ADMIN")).isTrue();
    assertThat(AuditActorTypes.isValid("system")).isTrue();
    assertThat(AuditActorTypes.isValid("nope")).isFalse();
    assertThat(AuditActorTypes.isValid(null)).isFalse();
    assertThat(AuditActorTypes.isValid("")).isFalse();
    assertThat(AuditActorTypes.normalize(null)).isEqualTo("ADMIN");
    assertThat(AuditActorTypes.normalize("  ")).isEqualTo("ADMIN");
    assertThat(AuditActorTypes.normalize(" automation ")).isEqualTo("AUTOMATION");
    assertThatThrownBy(() -> AuditActorTypes.normalize("x"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
