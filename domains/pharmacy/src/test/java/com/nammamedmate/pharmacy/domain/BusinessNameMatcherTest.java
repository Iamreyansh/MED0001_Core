package com.nammamedmate.pharmacy.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class BusinessNameMatcherTest {

  @Test
  void tokenizeAndLevenshteinEdgeCases() {
    assertThat(BusinessNameMatcher.tokenize(null)).isEmpty();
    assertThat(BusinessNameMatcher.tokenize("  ")).isEmpty();
    assertThat(BusinessNameMatcher.tokenize("Acme & Co.")).contains("ACME", "CO");
    assertThat(BusinessNameMatcher.levenshtein(List.of(), List.of())).isZero();
    assertThat(BusinessNameMatcher.levenshtein(List.of("A"), List.of("B"))).isEqualTo(1);
  }

  @Test
  void tokenizeSkipsBlankParts() {
    assertThat(BusinessNameMatcher.tokenize("A  B  C")).containsExactly("A", "B", "C");
  }
}
