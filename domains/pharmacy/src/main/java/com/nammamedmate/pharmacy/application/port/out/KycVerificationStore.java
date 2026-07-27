package com.nammamedmate.pharmacy.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface KycVerificationStore {

  record KycVerificationRecord(
      UUID id,
      UUID pharmacyId,
      UUID jobId,
      String verificationType,
      String apiProvider,
      Map<String, Object> requestPayload,
      Map<String, Object> responsePayload,
      String status,
      Map<String, Object> details,
      List<Map<String, Object>> adminFlags,
      int retryCount,
      Instant nextRetryAt,
      Instant verifiedAt,
      Instant createdAt) {}

  void insert(KycVerificationRecord record);

  Optional<KycVerificationRecord> findById(UUID id);

  List<KycVerificationRecord> findByJobId(UUID jobId);

  Optional<KycVerificationRecord> findByJobAndType(UUID jobId, String verificationType);

  void updateResult(
      UUID id,
      String status,
      Map<String, Object> responsePayload,
      Map<String, Object> details,
      List<Map<String, Object>> adminFlags,
      int retryCount,
      Instant nextRetryAt,
      Instant verifiedAt);

  List<KycVerificationRecord> findDueRetries(Instant now, int limit);
}
