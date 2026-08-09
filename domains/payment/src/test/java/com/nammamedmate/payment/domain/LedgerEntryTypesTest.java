package com.nammamedmate.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LedgerEntryTypesTest {

  @Test
  void knownAliasesAndApiNormalization() {
    assertThat(LedgerEntryTypes.isKnown("order_gmv")).isTrue();
    assertThat(LedgerEntryTypes.isKnown("NOPE")).isFalse();
    assertThat(LedgerEntryTypes.isKnown(null)).isFalse();
    assertThat(LedgerEntryTypes.toApiType("TCS_COLLECTED")).isEqualTo("TCS");
    assertThat(LedgerEntryTypes.toApiType("ORDER_GMV")).isEqualTo("ORDER_GMV");
    assertThat(LedgerEntryTypes.toApiType(null)).isNull();
    assertThat(LedgerEntryTypes.storageTypesForFilter("TCS"))
        .containsExactlyInAnyOrder("TCS", "TCS_COLLECTED");
    assertThat(LedgerEntryTypes.storageTypesForFilter("TCS_COLLECTED"))
        .containsExactlyInAnyOrder("TCS", "TCS_COLLECTED");
    assertThat(LedgerEntryTypes.storageTypesForFilter("COMMISSION")).containsExactly("COMMISSION");
    assertThat(LedgerEntryTypes.storageTypesForFilter("")).isEmpty();
    assertThat(LedgerEntryTypes.storageTypesForFilter(null)).isEmpty();
    assertThat(LedgerEntryTypes.toApiType("tcs_collected")).isEqualTo("TCS");
  }
}
