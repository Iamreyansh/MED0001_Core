package com.nammamedmate.order.domain;

import java.time.Instant;
import java.util.UUID;

public final class Refund {

  private final UUID id;
  private final UUID orderId;
  private final long amountPaise;
  private final RefundTo refundTo;
  private final String reason;
  private final String notes;
  private RefundStatus status;
  private final UUID issuedBy;
  private final RefundIssuedByType issuedByType;
  private String razorpayRefundId;
  private UUID walletTransactionId;
  private Instant processedAt;
  private String failedReason;
  private final String idempotencyKey;
  private final Instant createdAt;

  public Refund(
      UUID id,
      UUID orderId,
      long amountPaise,
      RefundTo refundTo,
      String reason,
      String notes,
      RefundStatus status,
      UUID issuedBy,
      RefundIssuedByType issuedByType,
      String razorpayRefundId,
      UUID walletTransactionId,
      Instant processedAt,
      String failedReason,
      String idempotencyKey,
      Instant createdAt) {
    this.id = id;
    this.orderId = orderId;
    this.amountPaise = amountPaise;
    this.refundTo = refundTo;
    this.reason = reason;
    this.notes = notes;
    this.status = status;
    this.issuedBy = issuedBy;
    this.issuedByType = issuedByType;
    this.razorpayRefundId = razorpayRefundId;
    this.walletTransactionId = walletTransactionId;
    this.processedAt = processedAt;
    this.failedReason = failedReason;
    this.idempotencyKey = idempotencyKey;
    this.createdAt = createdAt;
  }

  public UUID id() {
    return id;
  }

  public UUID orderId() {
    return orderId;
  }

  public long amountPaise() {
    return amountPaise;
  }

  public RefundTo refundTo() {
    return refundTo;
  }

  public String reason() {
    return reason;
  }

  public String notes() {
    return notes;
  }

  public RefundStatus status() {
    return status;
  }

  public UUID issuedBy() {
    return issuedBy;
  }

  public RefundIssuedByType issuedByType() {
    return issuedByType;
  }

  public String razorpayRefundId() {
    return razorpayRefundId;
  }

  public UUID walletTransactionId() {
    return walletTransactionId;
  }

  public Instant processedAt() {
    return processedAt;
  }

  public String failedReason() {
    return failedReason;
  }

  public String idempotencyKey() {
    return idempotencyKey;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public void markProcessed(Instant at) {
    this.status = RefundStatus.PROCESSED;
    this.processedAt = at;
  }

  public void markFailed(String reason, Instant at) {
    this.status = RefundStatus.FAILED;
    this.failedReason = reason;
    this.processedAt = at;
  }

  public void setRazorpayRefundId(String razorpayRefundId) {
    this.razorpayRefundId = razorpayRefundId;
  }

  public void setWalletTransactionId(UUID walletTransactionId) {
    this.walletTransactionId = walletTransactionId;
  }
}
