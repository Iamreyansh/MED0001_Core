package com.nammamedmate.prescription.application.port.out;

import com.nammamedmate.prescription.domain.ScheduleDrugRegisterEntry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScheduleDrugRegisterStore {

  record PharmacySnapshot(String name, String licenseNo) {}

  record ListFilter(
      String schedule,
      UUID pharmacyId,
      String drugName,
      Instant fromInclusive,
      Instant toExclusive,
      int page,
      int limit) {}

  record ListPage(List<ScheduleDrugRegisterEntry> entries, long total, long totalQtyIssued) {
    public ListPage {
      entries = entries == null ? List.of() : List.copyOf(entries);
    }
  }

  record ExportJob(
      UUID id,
      UUID pharmacyId,
      String schedule,
      LocalDate fromDate,
      LocalDate toDate,
      String status,
      String storageKey,
      Integer rowCount,
      UUID requestedBy,
      Instant generatedAt,
      Instant expiresAt,
      String errorMessage,
      Instant createdAt) {}

  void insert(ScheduleDrugRegisterEntry entry);

  Optional<Integer> latestRunningBalance(UUID pharmacyId, String schedule, String drugName);

  int nextSno(UUID pharmacyId, String schedule);

  int nextRxSeq(UUID pharmacyId, int year);

  Optional<PharmacySnapshot> pharmacy(UUID pharmacyId);

  Optional<String> staffName(UUID staffId);

  Optional<UUID> orderIdForRx(UUID rxId, UUID pharmacyId);

  ListPage list(ListFilter filter);

  List<ScheduleDrugRegisterEntry> listAll(ListFilter filter);

  int markArchivedPastRetention(Instant now);

  void insertExportJob(ExportJob job);

  Optional<ExportJob> findExportJob(UUID jobId);

  void updateExportJob(ExportJob job);

  boolean pharmacyExists(UUID pharmacyId);
}
