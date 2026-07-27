package com.nammamedmate.pharmacy.application.port.out;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KycDocumentStore {

  record KycDocumentRecord(
      UUID id,
      UUID pharmacyId,
      String documentType,
      String fileKey,
      String fileName,
      long fileSizeBytes,
      String fileMimeType,
      String status,
      String rejectionReason,
      LocalDate expiryDate,
      UUID verifiedBy,
      Instant verifiedAt,
      Instant createdAt,
      Instant updatedAt) {}

  record KycAccessAuditRecord(
      UUID id, UUID documentId, UUID pharmacyId, UUID adminId, Instant accessedAt) {}

  record KycExpiryAlertRecord(
      UUID id,
      UUID documentId,
      UUID pharmacyId,
      Instant alertAt,
      String template,
      Instant createdAt) {}

  void insert(KycDocumentRecord doc);

  Optional<KycDocumentRecord> findById(UUID docId, UUID pharmacyId);

  Optional<KycDocumentRecord> findByFileKey(String fileKey);

  List<KycDocumentRecord> findActiveByPharmacy(UUID pharmacyId);

  void updateStatus(
      UUID docId,
      String status,
      String rejectionReason,
      UUID verifiedBy,
      Instant verifiedAt,
      Instant updatedAt);

  void softDelete(UUID docId, Instant deletedAt);

  void setAllUploadedToUnderReview(UUID pharmacyId, Instant updatedAt);

  int countByPharmacyAndStatuses(UUID pharmacyId, List<String> statuses);

  void insertAccessAudit(KycAccessAuditRecord record);

  void insertExpiryAlert(KycExpiryAlertRecord record);

  boolean existsExpiryAlert(UUID documentId, String template);
}
