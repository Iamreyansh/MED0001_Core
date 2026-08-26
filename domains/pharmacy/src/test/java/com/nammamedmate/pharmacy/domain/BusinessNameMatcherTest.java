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

  @Test
  void tokenDistanceAndMismatchBranches() {
    assertThat(BusinessNameMatcher.tokenDistance("", "")).isZero();
    assertThat(BusinessNameMatcher.tokenDistance("Acme Pharma", "Acme Pharma")).isZero();
    assertThat(BusinessNameMatcher.levenshtein(List.of("A", "B"), List.of("A", "B"))).isZero();
    assertThat(
            BusinessNameMatcher.isSignificantMismatch(
                "Alpha Beta Gamma Delta Epsilon Zeta", "One Two Three Four Five Six Seven"))
        .isTrue();
    assertThat(BusinessNameMatcher.isSignificantMismatch("Same Name", "Same Name")).isFalse();
  }
}
