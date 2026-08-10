package com.nammamedmate.prescription.application.port.out;

import com.nammamedmate.prescription.domain.RxAuditEntry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface RxAuditStore {

  void insert(RxAuditEntry entry);

  Optional<RxAuditEntry> findByRxId(UUID rxId);

  void update(RxAuditEntry entry);

  void appendActivity(
      UUID id,
      UUID rxId,
      String action,
      UUID actorId,
      String actorRole,
      String payloadJson,
      Instant createdAt);

  List<Map<String, Object>> listActivity(UUID rxId);

  record ListFilter(
      String schedule,
      String status,
      String source,
      LocalDate fromDate,
      LocalDate toDate,
      String search,
      UUID pharmacyId,
      int page,
      int limit) {}

  record ListPage(List<ListRow> items, long total, Kpis kpis) {
    public ListPage {
      items = items == null ? List.of() : List.copyOf(items);
    }
  }

  record ListRow(
      RxAuditEntry entry,
      String patientName,
      String doctorName,
      boolean doctorVerified,
      String pharmacyName,
      Instant dispensedAt,
      String source,
      String drugSummary) {}

  record Kpis(
      long awaitingAudit,
      long flagged,
      long scheduleH1XCount,
      long verifiedToday,
      double complianceRatePct) {}

  ListPage list(ListFilter filter, Instant now);

  List<ListRow> listAllForExport(ListFilter filter);

  Optional<DuplicateMatch> findDuplicate(
      String patientName, String drugName, int quantity, Instant since, UUID excludeRxId);

  record DuplicateMatch(UUID rxId, UUID auditId) {}

  List<RxAuditEntry> findAwaitingPastDeadline(Instant now, int limit);

  int markOverdue(UUID id, Instant now);

  record Stats(
      Map<String, Double> complianceRateBySchedule,
      double flaggedRatePct,
      List<Map<String, Object>> topFlaggedPharmacies,
      List<Map<String, Object>> topFlaggedDrugs,
      long totalAudited,
      long totalVerified,
      long totalFlagged,
      long overdueAudits) {
    public Stats {
      complianceRateBySchedule =
          complianceRateBySchedule == null ? Map.of() : Map.copyOf(complianceRateBySchedule);
      topFlaggedPharmacies =
          topFlaggedPharmacies == null ? List.of() : List.copyOf(topFlaggedPharmacies);
      topFlaggedDrugs = topFlaggedDrugs == null ? List.of() : List.copyOf(topFlaggedDrugs);
    }
  }

  Stats statistics(LocalDate from, LocalDate to);

  Optional<OrderContext> orderContext(UUID orderId);

  record OrderContext(String orderNumber, String pharmacyName) {}

  Optional<String> pharmacyName(UUID pharmacyId);

  Optional<DispenseContext> dispenseContext(UUID rxId, UUID pharmacyId);

  record DispenseContext(
      Instant dispensedAt,
      List<Map<String, Object>> medicines,
      String patientName,
      String doctorName) {
    public DispenseContext {
      medicines = medicines == null ? List.of() : List.copyOf(medicines);
    }
  }
}
