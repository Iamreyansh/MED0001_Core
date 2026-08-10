package com.nammamedmate.teleconsult.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Composition-root bridge: teleconsult issues e-Rx without a domain→domain compile dep on
 * prescription.
 */
public interface EPrescriptionWritePort {

  Issued create(CreateRequest request);

  record CreateRequest(
      UUID id,
      UUID customerId,
      UUID teleconsultId,
      UUID doctorId,
      String doctorName,
      String qualification,
      String registrationNo,
      String specialty,
      String patientName,
      List<MedicineLine> medicines,
      boolean adviceOnly,
      String adviceText,
      String clinicalNotes,
      Instant issuedAt) {
    public CreateRequest {
      medicines = medicines == null ? List.of() : List.copyOf(medicines);
    }
  }

  record MedicineLine(
      String name,
      String dosage,
      String frequency,
      int quantity,
      String unit,
      Integer durationDays,
      String notes) {}

  record Issued(
      UUID prescriptionId,
      String rxId,
      String digitalSignatureHash,
      Instant expiresAt,
      Instant issuedAt,
      List<MedicineLine> medicines) {
    public Issued {
      medicines = medicines == null ? List.of() : List.copyOf(medicines);
    }
  }
}
