package com.nammamedmate.pos.application.port.out;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence for Khata ledger, repayments, limits, and reminder rate-limit log. */
public interface KhataStore extends PosKhataPort {

  long DEFAULT_CREDIT_LIMIT_PAISE = 5_000_000L;

  record CustomerInfo(UUID customerId, String name, String phone) {}

  record CustomerOutstandingRow(
      UUID customerId,
      String name,
      String phone,
      long outstandingPaise,
      LocalDate oldestUnpaidDate,
      int daysOverdue,
      boolean overdue) {}

  record UnpaidBillRow(
      UUID invoiceId,
      String invoiceNumber,
      LocalDate invoiceDate,
      long amountPaise,
      int daysSince) {}

  record LedgerRow(
      UUID entryId,
      String type,
      LocalDate date,
      String reference,
      long amountPaise,
      long runningBalancePaise,
      Instant createdAt) {}

  record AgingBuckets(long current0To30Paise, long overdue31To60Paise, long overdue60PlusPaise) {}

  record KpiSnapshot(
      long totalOutstandingPaise,
      long overdue30dPaise,
      long collectedThisMonthPaise,
      long creditGivenThisMonthPaise,
      long allTimeCreditGivenPaise) {}

  record RepaymentResult(
      UUID receiptId,
      String receiptNumber,
      String customerName,
      long amountPaise,
      String paymentMode,
      long previousOutstandingPaise,
      long newOutstandingPaise,
      String receiptPdfUrl,
      Instant createdAt) {}

  record PaymentHistoryRow(
      UUID receiptId,
      String receiptNumber,
      LocalDate date,
      String customerName,
      String customerPhone,
      String mode,
      long amountPaise,
      String note,
      long runningOutstandingAfterPaise) {}

  Optional<CustomerInfo> findCustomer(UUID customerId);

  boolean customerKnownToPharmacy(UUID pharmacyId, UUID customerId);

  List<CustomerOutstandingRow> listOutstanding(
      UUID pharmacyId, boolean overdueOnly, String sort, String q, int limit, int offset);

  long countOutstanding(UUID pharmacyId, boolean overdueOnly, String q);

  KpiSnapshot kpi(UUID pharmacyId, LocalDate monthStart, LocalDate monthEndExclusive);

  AgingBuckets aging(UUID pharmacyId, LocalDate today);

  List<UnpaidBillRow> unpaidBills(UUID pharmacyId, UUID customerId, LocalDate today);

  List<LedgerRow> ledgerDesc(UUID pharmacyId, UUID customerId);

  RepaymentResult recordRepayment(
      UUID pharmacyId,
      UUID customerId,
      long amountPaise,
      String paymentMode,
      String referenceNumber,
      String note,
      UUID collectedBy,
      Instant now);

  Optional<Instant> lastReminderAt(UUID pharmacyId, UUID customerId);

  void insertReminderLog(
      UUID id,
      UUID pharmacyId,
      UUID customerId,
      String channel,
      String template,
      String messageId,
      Instant sentAt);

  List<PaymentHistoryRow> paymentHistory(
      UUID pharmacyId,
      LocalDate fromDate,
      LocalDate toDate,
      String paymentMode,
      String q,
      int limit,
      int offset);

  long countPaymentHistory(
      UUID pharmacyId, LocalDate fromDate, LocalDate toDate, String paymentMode, String q);

  long paymentHistoryTotalPaise(
      UUID pharmacyId, LocalDate fromDate, LocalDate toDate, String paymentMode, String q);
}
