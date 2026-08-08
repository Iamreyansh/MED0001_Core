package com.nammamedmate.order.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import com.nammamedmate.order.application.port.out.PharmacyCandidatePort.PharmacyRow;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PharmacyCandidatePortTest {

  @Test
  void isOpenRequiresOnlineActiveNotForcedOffline() {
    UUID id = UUID.randomUUID();
    assertThat(row(id, true, false, "ACTIVE").isOpen()).isTrue();
    assertThat(row(id, false, false, "ACTIVE").isOpen()).isFalse();
    assertThat(row(id, true, true, "ACTIVE").isOpen()).isFalse();
    assertThat(row(id, true, false, "SUSPENDED").isOpen()).isFalse();
    assertThat(row(id, true, false, "PENDING_KYC").isOpen()).isFalse();
  }

  private static PharmacyRow row(UUID id, boolean online, boolean forcedOffline, String status) {
    return new PharmacyRow(
        id,
        "n",
        "a",
        "addr",
        null,
        null,
        12.9,
        77.6,
        online,
        forcedOffline,
        status,
        4,
        1,
        50,
        10.0);
  }
}
