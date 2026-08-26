package com.nammamedmate.payment.application.port.out;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Finance-facing access to the shared {@code refund} table (V032 + V061). */
public interface RefundFinancePort {

  record RefundRecord(
      UUID id,
      UUID orderId,
      String orderNumber,
      UUID customerId,
      String customerName,
      String customerPhone,
      String customerEmail,
      long amountPaise,
      long orderTotalPaise,
      long walletAppliedPaise,
      String refundTo,
      String status,
      String reason,
      String notes,
      String paymentMethod,
      String gatewayRefundId,
      String gatewayPaymentId,
      UUID walletTransactionId,
      boolean autoProcessed,
      UUID issuedBy,
      UUID processedBy,
      Instant processedAt,
      Instant completedAt,
      LocalDate expectedBy,
      String failedReason,
      Instant createdAt) {}

  record ListFilter(
      String storageStatus, String storageRefundTo, Instant createdFrom, int limit, int offset) {}

  record ListResult(List<RefundRecord> refunds, long total) {
    public ListResult {
      refunds = List.copyOf(refunds);
    }
  }

  record KpiSnapshot(
      long pendingCount,
      long pendingValuePaise,
      long processedToday,
      long failedToday,
      long overdueCount) {}

  Optional<RefundRecord> findById(UUID refundId);

  Optional<RefundRecord> findByGatewayRefundId(String gatewayRefundId);

  ListResult list(ListFilter filter);

  ListResult listForCustomer(UUID customerId, int limit, int offset);

  KpiSnapshot kpis(Instant dayStart, Instant dayEnd, Instant overdueBefore);

  /** Claim PENDING → INITIATED before Cashfree call (short TX). */
  boolean claimForProcess(UUID refundId, UUID processedBy, String notes, Instant now);

  boolean finalizeGatewayProcess(
      UUID refundId, String gatewayRefundId, LocalDate expectedBy, Instant now);

  /** Persist provider refund id while leaving INITIATED for webhook reconcile. */
  void attachGatewayRefundId(UUID refundId, String gatewayRefundId, Instant now);

  void markProcessFailed(UUID refundId, String reason, Instant now);

  boolean markCompleted(UUID refundId, Instant now);

  boolean markWalletCompleted(
      UUID refundId, UUID walletTxId, UUID processedBy, String notes, Instant now);
}
