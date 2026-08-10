package com.nammamedmate.prescription.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PrescriptionRecordTest {

  @Test
  void isExpired_byStatusOrTime() {
    Instant now = Instant.parse("2026-07-24T00:00:00Z");
    PrescriptionRecord expiredStatus =
        new PrescriptionRecord(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "UPLOADED",
            "EXPIRED",
            "k",
            1,
            "image/jpeg",
            null,
            null,
            null,
            null,
            "UPLOAD",
            null,
            null,
            null,
            now.plusSeconds(100),
            null,
            now,
            now,
            null);
    assertThat(expiredStatus.isExpired(now)).isTrue();

    PrescriptionRecord pastExpiry =
        new PrescriptionRecord(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "UPLOADED",
            "UPLOADED",
            "k",
            1,
            "image/jpeg",
            null,
            null,
            null,
            null,
            "UPLOAD",
            null,
            null,
            null,
            now.minusSeconds(1),
            null,
            now,
            now,
            null);
    assertThat(pastExpiry.isExpired(now)).isTrue();
    assertThat(pastExpiry.isExpired(now.minusSeconds(10))).isFalse();

    PrescriptionRecord nullExpiry =
        new PrescriptionRecord(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "UPLOADED",
            "UPLOADED",
            "k",
            1,
            "image/jpeg",
            null,
            null,
            null,
            null,
            "UPLOAD",
            null,
            null,
            null,
            null,
            null,
            now,
            now,
            null);
    assertThat(nullExpiry.isExpired(now)).isFalse();
  }
}
