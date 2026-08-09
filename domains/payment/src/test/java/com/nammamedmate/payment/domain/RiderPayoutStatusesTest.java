package com.nammamedmate.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class RiderPayoutStatusesTest {

  @Test
  void mapsStorageAndApiStatuses() {
    assertThat(RiderPayoutStatuses.toApiStatus(null)).isEqualTo("PENDING");
    assertThat(RiderPayoutStatuses.toApiStatus("BELOW_THRESHOLD_CARRIED_FORWARD"))
        .isEqualTo("BELOW_THRESHOLD_CARRIED");
    assertThat(RiderPayoutStatuses.toApiStatus("PENDING")).isEqualTo("PENDING");
    assertThat(RiderPayoutStatuses.toStorageFilter(null)).isNull();
    assertThat(RiderPayoutStatuses.toStorageFilter(" ")).isNull();
    assertThat(RiderPayoutStatuses.toStorageFilter("PENDING")).isEqualTo("PENDING");
    assertThat(RiderPayoutStatuses.toStorageFilter("BELOW_THRESHOLD_CARRIED"))
        .isEqualTo("BELOW_THRESHOLD_CARRIED_FORWARD");
    assertThat(RiderPayoutStatuses.toStorageFilter("BELOW_THRESHOLD_CARRIED_FORWARD"))
        .isEqualTo("BELOW_THRESHOLD_CARRIED_FORWARD");
    assertThat(RiderPayoutStatuses.toStorageFilter("HELD")).isEqualTo("HELD");
    assertThat(RiderPayoutStatuses.toStorageFilter("RELEASED")).isEqualTo("RELEASED");
    assertThat(RiderPayoutStatuses.toStorageFilter("FAILED")).isEqualTo("FAILED");
    assertThatThrownBy(() -> RiderPayoutStatuses.toStorageFilter("NOPE"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void isoWeekLabel() {
    assertThat(RiderPayoutStatuses.isoWeekLabel(null)).isEmpty();
    assertThat(RiderPayoutStatuses.isoWeekLabel(LocalDate.of(2026, 7, 14))).isEqualTo("2026-W29");
  }
}
