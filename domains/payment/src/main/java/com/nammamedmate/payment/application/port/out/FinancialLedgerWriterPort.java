package com.nammamedmate.payment.application.port.out;

import java.util.Map;
import java.util.UUID;

/** Thin append-only financial ledger writer (full query/export is STORY-008). */
public interface FinancialLedgerWriterPort {

  void append(
      String entryType,
      UUID referenceId,
      String referenceType,
      long creditPaise,
      long debitPaise,
      String description,
      Map<String, Object> metadata);
}
