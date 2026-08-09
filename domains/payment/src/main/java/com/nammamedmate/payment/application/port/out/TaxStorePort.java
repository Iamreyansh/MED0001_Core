package com.nammamedmate.payment.application.port.out;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence for tax_filing + tcs_register (EPIC-012 STORY-007). */
public interface TaxStorePort {

  record TaxFilingRecord(
      UUID id,
      String filingType,
      String period,
      LocalDate dueDate,
      String status,
      Instant filedAt,
      String referenceNumber,
      String notes,
      UUID markedBy,
      String generatedFilesJson,
      Instant createdAt,
      Instant updatedAt) {}

  record TcsRegisterRecord(
      UUID id,
      UUID pharmacyId,
      String month,
      String pharmacyName,
      String gstin,
      String pan,
      long gmvPaise,
      long tcsCollectedPaise,
      long cgstTcsPaise,
      long sgstTcsPaise,
      List<UUID> settlementIds,
      UUID gstr8FilingId) {
    public TcsRegisterRecord {
      settlementIds = settlementIds == null ? List.of() : List.copyOf(settlementIds);
    }
  }

  record TcsMonthTotals(long totalGmvPaise, long totalTcsPaise, int pharmaciesCount) {}

  record TcsPage(List<TcsRegisterRecord> entries, long total) {
    public TcsPage {
      entries = entries == null ? List.of() : List.copyOf(entries);
    }
  }

  record PharmacyCommissionRow(UUID pharmacyId, String pan, long commissionPaise) {}

  Optional<TaxFilingRecord> findFiling(UUID filingId);

  Optional<TaxFilingRecord> findFilingByTypeAndPeriod(String filingType, String period);

  List<TaxFilingRecord> listFilings(Integer year, String status);

  void insertFiling(TaxFilingRecord filing);

  void markFiled(
      UUID filingId,
      Instant filedAt,
      String referenceNumber,
      String notes,
      UUID markedBy,
      Instant now);

  void appendGeneratedFile(UUID filingId, String fileJsonObject, Instant now);

  void markOverduePending(LocalDate today, Instant now);

  Optional<TcsRegisterRecord> findTcs(UUID pharmacyId, String month);

  void upsertTcsOnRelease(
      UUID pharmacyId,
      String month,
      String pharmacyName,
      String gstin,
      String pan,
      UUID settlementId,
      long gmvPaise,
      long tcsPaise,
      Instant now);

  TcsMonthTotals tcsTotals(String month);

  TcsPage listTcs(String month, UUID pharmacyId, int limit, int offset);

  List<TcsRegisterRecord> listTcsAll(String month);

  void linkTcsToFiling(String month, UUID filingId, Instant now);

  /** Commission earned on RELEASED/PAID settlements with period_start in [from, to]. */
  List<PharmacyCommissionRow> commissionByPharmacy(LocalDate fromInclusive, LocalDate toInclusive);

  long totalCommissionPaise(LocalDate fromInclusive, LocalDate toInclusive);

  /** Sum of gateway_fee_paise on CAPTURED payments created in [from, to) UTC instants. */
  long gatewayFeesPaise(Instant fromInclusive, Instant toExclusive);
}
