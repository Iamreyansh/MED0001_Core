package com.nammamedmate.rider.application.port.out;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RiderKycDocumentStore {

  record DocumentRecord(
      UUID id,
      UUID riderId,
      String documentType,
      String documentNumber,
      String fileKey,
      String fileUrl,
      int fileSizeBytes,
      String mimeType,
      LocalDate expiryDate,
      boolean expiryAlertSent,
      String verificationStatus,
      String rejectionReason,
      Instant uploadedAt,
      Instant reviewedAt,
      UUID reviewedBy) {}

  void insert(DocumentRecord doc);

  void softDelete(UUID id, Instant deletedAt);

  Optional<DocumentRecord> findActiveByRiderAndType(UUID riderId, String documentType);

  List<DocumentRecord> findActiveByRider(UUID riderId);

  int countUploadsByRiderAndType(UUID riderId, String documentType);

  List<DocumentRecord> findDueForExpiryAlert(LocalDate onOrBefore, LocalDate after);

  void markExpiryAlertSent(UUID documentId);
}
