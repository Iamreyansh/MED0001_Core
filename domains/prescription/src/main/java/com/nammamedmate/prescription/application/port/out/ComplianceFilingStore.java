package com.nammamedmate.prescription.application.port.out;

import com.nammamedmate.prescription.domain.ComplianceFiling;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface ComplianceFilingStore {

  record ListFilter(
      String filingType,
      String status,
      Integer year,
      boolean includeArchived,
      int page,
      int limit) {}

  record ListPage(List<ComplianceFiling> filings, long total, long pending, long overdue) {
    public ListPage {
      filings = filings == null ? List.of() : List.copyOf(filings);
    }
  }

  record GenerateJob(
      UUID id,
      UUID filingId,
      String format,
      String status,
      String storageKey,
      Integer rowCount,
      UUID requestedBy,
      Instant generatedAt,
      Instant expiresAt,
      String errorMessage,
      Instant createdAt) {}

  record ActivityFilter(
      String action,
      UUID actorId,
      Instant fromInclusive,
      Instant toExclusive,
      int page,
      int limit) {}

  record ActivityPage(List<Map<String, Object>> items, long total) {
    public ActivityPage {
      items = items == null ? List.of() : List.copyOf(items);
    }
  }

  void insert(ComplianceFiling filing);

  Optional<ComplianceFiling> findById(UUID id);

  boolean existsTypePeriod(String filingType, LocalDate periodFrom, LocalDate periodTo);

  void update(ComplianceFiling filing);

  ListPage list(ListFilter filter);

  int markOverdue(LocalDate today, Instant now);

  List<ComplianceFiling> findPendingPastDue(LocalDate today);

  List<ComplianceFiling> findOverdueForEscalation(LocalDate escalationDay);

  void setOverdueAlerted(UUID id, Instant at);

  void setOverdueEscalation(UUID id, Instant at);

  int archiveOlderThan(LocalDate cutoff, Instant now);

  Optional<GenerateJob> findGeneratingJob(UUID filingId);

  void insertGenerateJob(GenerateJob job);

  Optional<GenerateJob> findGenerateJob(UUID jobId);

  void updateGenerateJob(GenerateJob job);

  void appendActivity(
      UUID id,
      UUID rxId,
      UUID doctorId,
      UUID filingId,
      String action,
      UUID actorId,
      String actorRole,
      String payloadJson,
      String ipAddress,
      Instant createdAt);

  ActivityPage listActivity(ActivityFilter filter);

  Optional<String> adminName(UUID adminId);
}
