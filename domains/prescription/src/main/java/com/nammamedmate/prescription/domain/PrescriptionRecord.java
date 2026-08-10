package com.nammamedmate.prescription.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PrescriptionRecord(
    UUID id,
    UUID customerId,
    String type,
    String status,
    String s3Key,
    long fileSizeBytes,
    String mimeType,
    String patientName,
    String notes,
    String doctorName,
    LocalDate prescriptionDate,
    String source,
    List<MedicineExtracted> medicinesExtracted,
    UUID associatedOrderId,
    UUID teleconsultId,
    Instant expiresAt,
    String rejectionReason,
    Instant createdAt,
    Instant updatedAt,
    Instant deletedAt) {

  public record MedicineExtracted(String name, String quantity, String dosage, String schedule) {}

  public PrescriptionRecord {
    medicinesExtracted = medicinesExtracted == null ? null : List.copyOf(medicinesExtracted);
  }

  public boolean isExpired(Instant now) {
    return "EXPIRED".equals(status) || (expiresAt != null && !expiresAt.isAfter(now));
  }
}
