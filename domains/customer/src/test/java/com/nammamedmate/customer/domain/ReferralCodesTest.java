package com.nammamedmate.customer.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ReferralCodesTest {

  @Test
  void normalize_andIsValidFormat() {
    assertThat(ReferralCodes.normalize(" medram7 ")).isEqualTo("MEDRAM7");
    assertThat(ReferralCodes.isValidFormat("MEDRAM7")).isTrue();
    assertThat(ReferralCodes.isValidFormat("medram7")).isTrue();
    // Apply-time format allows any 7-char alphanumeric code; prefix is not enforced (→ 404 later).
    assertThat(ReferralCodes.isValidFormat("ABCDEFG")).isTrue();
    assertThat(ReferralCodes.isValidFormat("MEDRA")).isFalse();
    assertThat(ReferralCodes.isValidFormat("TOOLONG8")).isFalse();
    assertThat(ReferralCodes.isValidFormat(null)).isFalse();
  }

  @Test
  void generateUnique_retriesUntilFree() {
    AtomicInteger calls = new AtomicInteger();
    String code =
        ReferralCodes.generateUnique(
            c -> {
              calls.incrementAndGet();
              return calls.get() < 3;
            });
    assertThat(code).hasSize(7).startsWith("MED");
    assertThat(ReferralCodes.isValidFormat(code)).isTrue();
    assertThat(calls.get()).isEqualTo(3);
  }

  @Test
  void generateUnique_exhausted_throws() {
    assertThatThrownBy(() -> ReferralCodes.generateUnique(c -> true))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void isValidFormat_rejectsBadSuffixChar() {
    assertThat(ReferralCodes.isValidFormat("MEDRAM!")).isFalse();
  }
}
