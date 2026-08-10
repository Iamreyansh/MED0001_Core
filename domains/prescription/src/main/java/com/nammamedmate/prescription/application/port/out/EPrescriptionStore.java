package com.nammamedmate.prescription.application.port.out;

import com.nammamedmate.prescription.domain.EPrescriptionRecord;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface EPrescriptionStore {

  long nextRxSequence();

  void insert(EPrescriptionRecord record);

  Optional<EPrescriptionRecord> findById(UUID id);

  Optional<EPrescriptionRecord> findByIdForCustomer(UUID id, UUID customerId);

  Optional<EPrescriptionRecord> findByTeleconsultId(UUID teleconsultId);

  void updatePdf(
      UUID id, String pdfS3Key, long fileSizeBytes, Instant pdfGeneratedAt, Instant updatedAt);
}
