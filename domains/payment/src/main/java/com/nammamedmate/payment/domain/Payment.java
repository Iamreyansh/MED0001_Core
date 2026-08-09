package com.nammamedmate.payment.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class Payment {

  private final UUID id;
  private final UUID orderId;
  private final UUID customerId;
  private final long amountPaise;
  private final long walletPortionPaise;
  private final long gatewayPortionPaise;
  private final String currency;
  private final PaymentMethod method;
  private PaymentStatus status;
  private String razorpayOrderId;
  private String razorpayPaymentId;
  private String razorpaySignature;
  private Long gatewayFeePaise;
  private String gatewayResponseJson;
  private final List<String> webhookEvents;
  private Instant capturedAt;
  private Instant failedAt;
  private String failureReason;
  private final String idempotencyKey;
  private final Instant createdAt;
  private Instant updatedAt;

  public Payment(
      UUID id,
      UUID orderId,
      UUID customerId,
      long amountPaise,
      long walletPortionPaise,
      long gatewayPortionPaise,
      String currency,
      PaymentMethod method,
      PaymentStatus status,
      String razorpayOrderId,
      String razorpayPaymentId,
      String razorpaySignature,
      Long gatewayFeePaise,
      String gatewayResponseJson,
      List<String> webhookEvents,
      Instant capturedAt,
      Instant failedAt,
      String failureReason,
      String idempotencyKey,
      Instant createdAt,
      Instant updatedAt) {
    this.id = Objects.requireNonNull(id, "id");
    this.orderId = Objects.requireNonNull(orderId, "orderId");
    this.customerId = Objects.requireNonNull(customerId, "customerId");
    this.amountPaise = amountPaise;
    this.walletPortionPaise = walletPortionPaise;
    this.gatewayPortionPaise = gatewayPortionPaise;
    this.currency = normalizeCurrency(currency);
    this.method = Objects.requireNonNull(method, "method");
    this.status = Objects.requireNonNull(status, "status");
    this.razorpayOrderId = razorpayOrderId;
    this.razorpayPaymentId = razorpayPaymentId;
    this.razorpaySignature = razorpaySignature;
    this.gatewayFeePaise = gatewayFeePaise;
    this.gatewayResponseJson = gatewayResponseJson;
    this.webhookEvents = webhookEvents == null ? new ArrayList<>() : new ArrayList<>(webhookEvents);
    this.capturedAt = capturedAt;
    this.failedAt = failedAt;
    this.failureReason = failureReason;
    this.idempotencyKey = idempotencyKey;
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
  }

  public UUID id() {
    return id;
  }

  public UUID orderId() {
    return orderId;
  }

  public UUID customerId() {
    return customerId;
  }

  public long amountPaise() {
    return amountPaise;
  }

  public long walletPortionPaise() {
    return walletPortionPaise;
  }

  public long gatewayPortionPaise() {
    return gatewayPortionPaise;
  }

  public String currency() {
    return currency;
  }

  public PaymentMethod method() {
    return method;
  }

  public PaymentStatus status() {
    return status;
  }

  public String razorpayOrderId() {
    return razorpayOrderId;
  }

  public String razorpayPaymentId() {
    return razorpayPaymentId;
  }

  public String razorpaySignature() {
    return razorpaySignature;
  }

  public Long gatewayFeePaise() {
    return gatewayFeePaise;
  }

  public String gatewayResponseJson() {
    return gatewayResponseJson;
  }

  public List<String> webhookEvents() {
    return List.copyOf(webhookEvents);
  }

  public Instant capturedAt() {
    return capturedAt;
  }

  public Instant failedAt() {
    return failedAt;
  }

  public String failureReason() {
    return failureReason;
  }

  public String idempotencyKey() {
    return idempotencyKey;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant updatedAt() {
    return updatedAt;
  }

  public void capture(
      String razorpayPaymentId,
      String signature,
      Long gatewayFeePaise,
      String gatewayResponseJson,
      Instant now) {
    this.status = PaymentStatus.CAPTURED;
    this.razorpayPaymentId = razorpayPaymentId;
    this.razorpaySignature = signature;
    this.gatewayFeePaise = gatewayFeePaise;
    this.gatewayResponseJson = gatewayResponseJson;
    this.capturedAt = now;
    this.updatedAt = now;
  }

  public void fail(String reason, Instant now) {
    this.status = PaymentStatus.FAILED;
    this.failureReason = reason;
    this.failedAt = now;
    this.updatedAt = now;
  }

  public void fail(
      String razorpayPaymentId, String reason, String gatewayResponseJson, Instant now) {
    if (razorpayPaymentId != null) {
      if (!razorpayPaymentId.isBlank()) {
        this.razorpayPaymentId = razorpayPaymentId;
      }
    }
    this.gatewayResponseJson = gatewayResponseJson;
    fail(reason, now);
  }

  public void appendWebhookEvent(String event) {
    if (event != null) {
      if (!event.isBlank()) {
        webhookEvents.add(event);
      }
    }
  }

  public void touch(Instant now) {
    this.updatedAt = now;
  }

  private static String normalizeCurrency(String currency) {
    if (currency == null) {
      return "INR";
    }
    if (currency.isBlank()) {
      return "INR";
    }
    return currency;
  }
}
