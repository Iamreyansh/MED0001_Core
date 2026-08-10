package com.nammamedmate.prescription.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PharmacyRxQueueEntryTest {

  @Test
  void overdueHelpers() {
    Instant received = Instant.parse("2026-07-24T05:10:00Z");
    PharmacyRxQueueEntry e =
        new PharmacyRxQueueEntry(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,
            received,
            "PENDING_REVIEW",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            null,
            received,
            received,
            null);
    assertThat(e.slaDeadline()).isEqualTo(Instant.parse("2026-07-24T07:10:00Z"));
    assertThat(e.isOverdue(Instant.parse("2026-07-24T07:15:00Z"))).isTrue();
    assertThat(e.overdueByMinutes(Instant.parse("2026-07-24T07:15:00Z"))).isEqualTo(5L);
    assertThat(e.isOverdue(Instant.parse("2026-07-24T07:00:00Z"))).isFalse();
    assertThat(e.overdueByMinutes(Instant.parse("2026-07-24T07:00:00Z"))).isZero();
    PharmacyRxQueueEntry approved =
        new PharmacyRxQueueEntry(
            e.id(),
            e.rxId(),
            e.pharmacyId(),
            null,
            received,
            "APPROVED",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            null,
            received,
            received,
            null);
    assertThat(approved.isOverdue(Instant.parse("2026-07-24T10:00:00Z"))).isFalse();
  }
}
