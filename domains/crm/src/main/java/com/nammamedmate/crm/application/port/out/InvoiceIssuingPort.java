package com.nammamedmate.crm.application.port.out;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Create persisted saas_invoice rows for subscribe / upgrade / renew. */
public interface InvoiceIssuingPort {

  record LineDraft(String description, long amountPaise, String itemType) {}

  /**
   * Persist invoice + line items with GST. Returns invoice id.
   *
   * @param status {@code PAID} or {@code DUE}
   */
  UUID issue(
      UUID accountId,
      UUID subscriptionId,
      String planName,
      LocalDate periodFrom,
      LocalDate periodTo,
      LocalDate dueDate,
      List<LineDraft> lines,
      String status,
      Instant paidAt,
      String paymentMode,
      String referenceNumber);
}
