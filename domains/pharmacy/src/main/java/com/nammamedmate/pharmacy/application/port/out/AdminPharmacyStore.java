package com.nammamedmate.pharmacy.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface AdminPharmacyStore {

  record AdminListRow(
      UUID pharmacyId,
      String code,
      String businessName,
      String ownerName,
      String phone,
      String email,
      UUID zoneId,
      String zoneName,
      String status,
      String plan,
      boolean online,
      Instant submittedAt,
      Instant createdAt,
      Instant ageAnchor,
      String autoKycStatus,
      BigDecimal rating,
      int reviewCount,
      int ordersToday,
      long gmvTodayPaise,
      BigDecimal fillRatePct,
      BigDecimal commissionPct,
      long netPayoutPaise,
      Instant metricsAsOf) {}

  record AdminDetailRow(
      UUID pharmacyId,
      String code,
      String businessName,
      String ownerName,
      String phone,
      String email,
      String businessType,
      Map<String, Object> address,
      String gstin,
      String drugLicenceNumber,
      String fssaiNumber,
      String panNumber,
      String status,
      String plan,
      BigDecimal commissionPct,
      UUID zoneId,
      String zoneName,
      boolean online,
      boolean canReapply,
      Instant kycSubmittedAt,
      Instant createdAt,
      Instant updatedAt,
      Instant planExpiresAt,
      String rejectionReason,
      String rejectionDetails,
      Instant activatedAt,
      Instant suspendedAt,
      String suspendType,
      Instant kycSlaResetAt) {
    public AdminDetailRow {
      address = address == null ? Map.of() : Map.copyOf(address);
    }
  }

  record ListFilter(
      String status,
      UUID zoneId,
      String plan,
      Boolean online,
      String search,
      String sort,
      String order,
      int limit,
      int offset) {}

  record PageResult(List<AdminListRow> rows, long total) {
    public PageResult {
      rows = List.copyOf(rows);
    }
  }

  record DirectorySummary(
      long totalActive,
      long pendingKyc,
      long kycSubmitted,
      long suspended,
      long rejected,
      long currentlyOnline,
      long ordersToday,
      long gmvTodayPaise,
      long commissionTodayPaise,
      long payoutDuePaise,
      Instant dataAsOf) {}

  PageResult list(ListFilter filter);

  /** Same filters as list, capped at {@code limit} (export uses up to 10_000). */
  List<AdminListRow> exportRows(ListFilter filter);

  DirectorySummary directorySummary(Instant asOf);

  Optional<AdminDetailRow> findDetail(UUID pharmacyId);

  Map<String, String> documentStatusSummary(UUID pharmacyId);

  String nextCode();

  void approve(
      UUID pharmacyId,
      BigDecimal commissionPct,
      UUID zoneId,
      Instant activatedAt,
      Instant updatedAt);

  void reject(
      UUID pharmacyId,
      String rejectionReason,
      String rejectionDetails,
      boolean canReapply,
      Instant rejectedAt);

  void suspend(UUID pharmacyId, String suspendType, boolean canReapply, Instant suspendedAt);

  void reactivate(UUID pharmacyId, Instant reactivatedAt, boolean canReapply);

  void resetKycSla(UUID pharmacyId, Instant slaResetAt);

  /** Active pharmacies for nightly performance aggregation. */
  List<UUID> listActivePharmacyIds();

  /** Directory rows for explicit pharmacy IDs (bulk export). */
  List<AdminListRow> listByIds(List<UUID> pharmacyIds);

  void updateCommissionPct(UUID pharmacyId, BigDecimal commissionPct, Instant updatedAt);
}
