package com.nammamedmate.payment.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read-side financial ledger queries (append-only table; STORY-008). */
public interface FinancialLedgerQueryPort {

  record LedgerRow(
      UUID id,
      String entryType,
      UUID referenceId,
      String referenceType,
      long creditPaise,
      long debitPaise,
      long runningBalancePaise,
      String description,
      Instant createdAt) {}

  record LedgerPage(List<LedgerRow> rows, long total) {
    public LedgerPage {
      rows = rows == null ? List.of() : List.copyOf(rows);
    }
  }

  record DayKpis(long gmvTodayPaise, long commissionTodayPaise, long gatewayFeeTodayPaise) {}

  LedgerPage list(
      String[] entryTypes,
      Instant fromInclusive,
      Instant toExclusive,
      int page,
      int limit,
      boolean ascending);

  /** All matching rows with running balance (for CSV export); ordered created_at ASC. */
  List<LedgerRow> listAllForExport(String[] entryTypes, Instant fromInclusive, Instant toExclusive);

  DayKpis dayKpis(Instant dayStartInclusive, Instant dayEndExclusive);
}
