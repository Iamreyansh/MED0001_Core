package com.nammamedmate.prescription.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RxAuditEntryTest {

  @Test
  void deadlineRankAndOverdue() {
    assertThat(RxAuditEntry.deadlineFor("H1")).isEqualTo(Duration.ofHours(24));
    assertThat(RxAuditEntry.deadlineFor("X")).isEqualTo(Duration.ofHours(24));
    assertThat(RxAuditEntry.deadlineFor("H")).isEqualTo(Duration.ofDays(7));
    assertThat(RxAuditEntry.deadlineFor("NONE")).isEqualTo(Duration.ofHours(24));
    assertThat(RxAuditEntry.higher("H", "H1")).isEqualTo("H1");
    assertThat(RxAuditEntry.higher("X", "H1")).isEqualTo("X");
    assertThat(RxAuditEntry.higher(null, "H")).isEqualTo("H");
    assertThat(RxAuditEntry.rank(null)).isZero();

    Instant created = Instant.parse("2026-07-24T04:00:00Z");
    RxAuditEntry e =
        new RxAuditEntry(
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,
            UUID.randomUUID(),
            "H1",
            "AWAITING_AUDIT",
            created.plus(Duration.ofHours(24)),
            false,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            created);
    assertThat(e.isOverdue(created.plus(Duration.ofHours(25)))).isTrue();
    assertThat(e.isOverdue(created.plus(Duration.ofHours(1)))).isFalse();
    assertThat(e.hoursSinceDispense(created.plus(Duration.ofHours(3)))).isEqualTo(3.0);
    assertThat(
            new RxAuditEntry(
                    e.id(),
                    e.rxId(),
                    null,
                    e.pharmacyId(),
                    "H1",
                    "AWAITING_AUDIT",
                    e.auditDeadline(),
                    false,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null)
                .hoursSinceDispense(created))
        .isZero();
  }
}
