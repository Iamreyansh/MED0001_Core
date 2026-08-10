package com.nammamedmate.teleconsult.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.nammamedmate.kernel.id.Ids;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConsultTest {

  @Test
  void activeCancellableAndTransitions() {
    Instant now = Instant.parse("2026-07-24T10:00:00Z");
    Consult active =
        new Consult(
            Ids.newId(),
            Ids.newId(),
            null,
            "Ravi",
            "+91",
            Consult.SLOT_NOW,
            null,
            null,
            null,
            null,
            false,
            "GENERAL",
            Consult.STATUS_REQUESTED,
            null,
            null,
            null,
            null,
            false,
            null,
            null,
            null,
            null,
            null,
            now,
            now,
            null);
    assertThat(active.isActive()).isTrue();
    assertThat(active.customerCancellable()).isTrue();
    assertThat(active.symptoms()).isEmpty();
    assertThat(active.medicinesNeedingRx()).isEmpty();

    assertThat(Consult.canTransition(Consult.STATUS_REQUESTED, Consult.STATUS_IN_CALL)).isFalse();
    assertThat(Consult.canTransition(Consult.STATUS_REQUESTED, Consult.STATUS_DOCTOR_REVIEWING))
        .isTrue();
    assertThat(Consult.canTransition(Consult.STATUS_IN_CALL, Consult.STATUS_COMPLETED)).isTrue();
    assertThat(Consult.canTransition(Consult.STATUS_COMPLETED, Consult.STATUS_CANCELLED)).isFalse();
    assertThat(Consult.canTransition(null, Consult.STATUS_CANCELLED)).isFalse();
    assertThat(Consult.canTransition(Consult.STATUS_REQUESTED, null)).isFalse();
    assertThat(Consult.canTransition("UNKNOWN", Consult.STATUS_CANCELLED)).isFalse();

    Consult done =
        new Consult(
            active.id(),
            active.customerId(),
            null,
            "Ravi",
            "+91",
            Consult.SLOT_NOW,
            null,
            List.of("a"),
            List.of(new Consult.MedicineNeed("m", "REFILL")),
            null,
            false,
            "GENERAL",
            Consult.STATUS_COMPLETED,
            null,
            null,
            null,
            null,
            false,
            null,
            null,
            null,
            null,
            null,
            now,
            now,
            null);
    assertThat(done.isActive()).isFalse();
    assertThat(done.customerCancellable()).isFalse();

    Consult cancelled =
        new Consult(
            active.id(),
            active.customerId(),
            null,
            "Ravi",
            "+91",
            Consult.SLOT_NOW,
            null,
            List.of(),
            List.of(),
            null,
            false,
            "GENERAL",
            Consult.STATUS_CANCELLED,
            null,
            null,
            null,
            null,
            false,
            null,
            null,
            null,
            null,
            null,
            now,
            now,
            null);
    assertThat(cancelled.isActive()).isFalse();

    Consult reviewing =
        new Consult(
            active.id(),
            active.customerId(),
            null,
            "Ravi",
            "+91",
            Consult.SLOT_NOW,
            null,
            List.of(),
            List.of(),
            null,
            false,
            "GENERAL",
            Consult.STATUS_DOCTOR_REVIEWING,
            null,
            null,
            null,
            null,
            false,
            null,
            null,
            null,
            null,
            null,
            now,
            now,
            null);
    assertThat(reviewing.customerCancellable()).isTrue();
  }
}
