package com.nammamedmate.prescription.domain;

import com.nammamedmate.prescription.domain.EPrescriptionSignature.MedicinePrescribed;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** e-Prescription row (type=E_PRESCRIPTION, source=TELECONSULT). */
public record EPrescriptionRecord(
    UUID id,
    String rxId,
    UUID customerId,
    UUID teleconsultId,
    UUID doctorId,
    String doctorName,
    String patientName,
    List<MedicinePrescribed> medicines,
    boolean adviceOnly,
    String adviceText,
    String clinicalNotes,
    String digitalSignatureHash,
    boolean verified,
    String seal,
    String status,
    String s3Key,
    String pdfS3Key,
    Instant pdfGeneratedAt,
    long fileSizeBytes,
    UUID associatedOrderId,
    Instant issuedAt,
    Instant expiresAt,
    Instant createdAt,
    Instant updatedAt,
    Instant deletedAt) {

  public EPrescriptionRecord {
    medicines = medicines == null ? List.of() : List.copyOf(medicines);
  }

  public boolean isExpired(Instant now) {
    return "EXPIRED".equals(status) || (expiresAt != null && !expiresAt.isAfter(now));
  }
}
