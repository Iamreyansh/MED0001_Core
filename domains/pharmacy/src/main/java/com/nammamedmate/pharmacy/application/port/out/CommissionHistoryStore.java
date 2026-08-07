package com.nammamedmate.pharmacy.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CommissionHistoryStore {

  record CommissionHistoryRow(
      UUID id,
      UUID pharmacyId,
      BigDecimal previousCommissionPct,
      BigDecimal newCommissionPct,
      LocalDate effectiveFrom,
      String reason,
      String notes,
      UUID changedBy,
      Instant changedAt,
      Instant appliedAt) {}

  Optional<CommissionHistoryRow> findPendingChange(UUID pharmacyId);

  void insert(CommissionHistoryRow row);

  List<CommissionHistoryRow> findDueForApply(LocalDate effectiveDate);

  void markApplied(UUID id, Instant appliedAt);
}
