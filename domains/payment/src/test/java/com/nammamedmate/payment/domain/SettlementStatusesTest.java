package com.nammamedmate.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SettlementStatusesTest {

  @Test
  void mapsApiAndStorageStatuses() {
    assertThat(SettlementStatuses.toStorageFilter("PENDING"))
        .isEqualTo(SettlementStatuses.STORAGE_PENDING);
    assertThat(SettlementStatuses.toStorageFilter("RELEASED"))
        .isEqualTo(SettlementStatuses.STORAGE_RELEASED);
    assertThat(SettlementStatuses.toStorageFilter("HELD"))
        .isEqualTo(SettlementStatuses.STORAGE_HELD);
    assertThat(SettlementStatuses.toStorageFilter("BELOW_THRESHOLD_CARRIED"))
        .isEqualTo(SettlementStatuses.STORAGE_BELOW);
    assertThat(SettlementStatuses.toStorageFilter("PAID"))
        .isEqualTo(SettlementStatuses.STORAGE_PAID);
    assertThat(SettlementStatuses.toStorageFilter("FAILED"))
        .isEqualTo(SettlementStatuses.STORAGE_FAILED);
    assertThat(SettlementStatuses.toStorageFilter("PENDING_RELEASE"))
        .isEqualTo(SettlementStatuses.STORAGE_PENDING);
    assertThat(SettlementStatuses.toStorageFilter(null)).isNull();
    assertThat(SettlementStatuses.toStorageFilter("  ")).isNull();
    assertThatThrownBy(() -> SettlementStatuses.toStorageFilter("NOPE"))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(SettlementStatuses.toApiStatus("PENDING_RELEASE")).isEqualTo("PENDING");
    assertThat(SettlementStatuses.toApiStatus("PAID")).isEqualTo("RELEASED");
    assertThat(SettlementStatuses.toApiStatus("RELEASED")).isEqualTo("RELEASED");
    assertThat(SettlementStatuses.toApiStatus("HELD")).isEqualTo("HELD");
    assertThat(SettlementStatuses.toApiStatus("BELOW_THRESHOLD_CARRIED"))
        .isEqualTo("BELOW_THRESHOLD_CARRIED");
    assertThat(SettlementStatuses.toApiStatus("FAILED")).isEqualTo("FAILED");
    assertThat(SettlementStatuses.toApiStatus(null)).isEqualTo("PENDING");
    assertThat(SettlementStatuses.toApiStatus("CUSTOM")).isEqualTo("CUSTOM");
  }
}
