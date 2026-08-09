package com.nammamedmate.pos.application.port.out;

import java.util.UUID;

/** Khata ledger port — CREDIT checkout + mark-paid repayment. */
public interface PosKhataPort {

  /** Outstanding balance in paise for pharmacy+customer. */
  long outstandingPaise(UUID pharmacyId, UUID customerId);

  /** Credit limit in paise; default ₹50,000 when no override row. Stub may return unlimited. */
  long creditLimitPaise(UUID pharmacyId, UUID customerId);

  /**
   * Ensure pharmacy↔customer link exists (inserts default {@code khata_customer_limit} if missing).
   * Called on POS attach and CREDIT checkout so khata APIs can authorize access.
   */
  void ensureCustomerKnown(UUID pharmacyId, UUID customerId);

  /** Post CREDIT sale to ledger as DEBIT entry. */
  void postCreditSale(UUID customerId, UUID invoiceId, long amountPaise, UUID pharmacyId);

  /**
   * Record CREDIT repayment (e.g. mark-paid). Returns receipt number {@code RCPT-YYYY-MM-NNNNNN}.
   */
  String recordCreditRepayment(
      UUID customerId,
      UUID invoiceId,
      long amountPaise,
      UUID pharmacyId,
      String paymentMode,
      String referenceNumber,
      String note,
      UUID collectedBy);
}
