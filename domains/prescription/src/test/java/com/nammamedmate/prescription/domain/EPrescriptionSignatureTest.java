package com.nammamedmate.prescription.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.prescription.domain.EPrescriptionSignature.MedicinePrescribed;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EPrescriptionSignatureTest {

  @Test
  void computesDeterministicHashAndVerifies() {
    UUID doctor = UUID.fromString("11111111-1111-4111-8111-111111111111");
    Instant issued = Instant.parse("2026-07-24T10:40:00Z");
    List<MedicinePrescribed> meds =
        List.of(
            new MedicinePrescribed(
                "Glipizide 5mg", "5mg", "0-1-0", 30, "tablets", 30, "before meals"),
            new MedicinePrescribed(
                "Metformin 500mg", "500mg", "1-0-1", 60, "tablets", 30, "with food"));

    String hash = EPrescriptionSignature.compute(doctor, "Ravi Kumar", meds, issued);
    assertThat(hash).hasSize(64);
    assertThat(EPrescriptionSignature.verify(hash, doctor, "Ravi Kumar", meds, issued)).isTrue();
    assertThat(EPrescriptionSignature.verify(hash, doctor, "Other", meds, issued)).isFalse();
    assertThat(EPrescriptionSignature.verify(null, doctor, "Ravi Kumar", meds, issued)).isFalse();
    assertThat(EPrescriptionSignature.verify("  ", doctor, "Ravi Kumar", meds, issued)).isFalse();
  }

  @Test
  void canonicalJsonSortsByNameAndAlphaKeysNoWhitespace() {
    List<MedicinePrescribed> meds =
        List.of(
            new MedicinePrescribed("B", "1", "od", 1, "ml", null, null),
            new MedicinePrescribed("A", "2", "bd", 2, "tablets", 7, "n"));
    String json = EPrescriptionSignature.canonicalMedicinesJson(meds);
    assertThat(json).doesNotContain(" ");
    assertThat(json.indexOf("\"name\":\"A\"")).isLessThan(json.indexOf("\"name\":\"B\""));
    assertThat(json).startsWith("[{");
    assertThat(EPrescriptionSignature.canonicalMedicinesJson(null)).isEqualTo("[]");
    assertThat(EPrescriptionSignature.canonicalMedicinesJson(List.of())).isEqualTo("[]");
  }

  @Test
  void medicineToApiMapIncludesOptionalFields() {
    MedicinePrescribed m = new MedicinePrescribed("X", "10mg", "1-0-0", 10, "capsules", 5, "note");
    assertThat(m.toApiMap()).containsEntry("duration_days", 5).containsEntry("notes", "note");
  }

  @Test
  void digestAlgoUnavailable_nullPatient_andNullMedicineFields() {
    assertThatThrownBy(() -> EPrescriptionSignature.digestHex("NOT-A-REAL-ALGO", "x"))
        .isInstanceOf(IllegalStateException.class);
    UUID doctor = UUID.randomUUID();
    Instant issued = Instant.parse("2026-07-24T10:40:00Z");
    assertThat(EPrescriptionSignature.compute(doctor, null, List.of(), issued)).hasSize(64);
    String json =
        EPrescriptionSignature.canonicalMedicinesJson(
            List.of(
                new MedicinePrescribed(null, null, null, 1, null, null, null),
                new MedicinePrescribed("A", "1", "od", 1, "ml", null, null)));
    assertThat(json).contains("\"name\":\"\"").contains("\"name\":\"A\"");
  }
}
