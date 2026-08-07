package com.nammamedmate.pharmacy.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CustomerNameMaskerTest {

  @Test
  void masksLastNameToInitial() {
    assertThat(CustomerNameMasker.mask("Priya Sharma")).isEqualTo("Priya S.");
    assertThat(CustomerNameMasker.mask("Arun M.")).isEqualTo("Arun M.");
    assertThat(CustomerNameMasker.mask("Single")).isEqualTo("Single");
    assertThat(CustomerNameMasker.mask(null)).isEmpty();
    assertThat(CustomerNameMasker.mask("  ")).isEmpty();
    assertThat(CustomerNameMasker.mask("A ")).isEqualTo("A");
  }
}
