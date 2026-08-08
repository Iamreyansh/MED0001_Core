package com.nammamedmate.order.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class Order {

  public static final Duration DELIVERY_SLA = Duration.ofMinutes(30);
  public static final Duration ACCEPTANCE_WINDOW = Duration.ofMinutes(10);
  public static final Duration RIDER_ASSIGN_ALERT = Duration.ofMinutes(30);
  public static final Duration SLA_RISK_THRESHOLD = Duration.ofMinutes(5);

  private final UUID id;
  private final String orderNumber;
  private final UUID customerId;
  private final UUID pharmacyId;
  private final UUID cartId;
  private final List<OrderItemSnapshot> items;
  private final long itemTotalPaise;
  private final String couponCode;
  private final long couponDiscountPaise;
  private final long deliveryFeePaise;
  private final long handlingFeePaise;
  private final long walletAppliedPaise;
  private final long totalPayablePaise;
  private final PaymentMethod paymentMethod;
  private PaymentStatus paymentStatus;
  private String razorpayOrderId;
  private String razorpayPaymentId;
  private final UUID prescriptionId;
  private final UUID deliveryAddressId;
  private final String deliveryInstructions;
  private OrderStatus status;
  private UUID riderId;
  private String deliveryOtpHash;
  private final String placementIdempotencyKey;
  private Instant confirmedAt;
  private Instant estimatedDeliveryAt;
  private final Instant createdAt;
  private Instant updatedAt;
  private Instant acceptedAt;
  private Instant deliveredAt;
  private Instant slaDeadline;
  private boolean slaBreached;
  private Instant riderAssignedAt;
  private Instant otpVerifiedAt;
  private Instant readyForPickupAt;
  private Instant riderEscalationAt;
  private String cancelReason;

  /** STORY-004 compatible constructor; lifecycle fields null/false. */
  public Order(
      UUID id,
      String orderNumber,
      UUID customerId,
      UUID pharmacyId,
      UUID cartId,
      List<OrderItemSnapshot> items,
      long itemTotalPaise,
      String couponCode,
      long couponDiscountPaise,
      long deliveryFeePaise,
      long handlingFeePaise,
      long walletAppliedPaise,
      long totalPayablePaise,
      PaymentMethod paymentMethod,
      PaymentStatus paymentStatus,
      String razorpayOrderId,
      String razorpayPaymentId,
      UUID prescriptionId,
      UUID deliveryAddressId,
      String deliveryInstructions,
      OrderStatus status,
      UUID riderId,
      String deliveryOtpHash,
      String placementIdempotencyKey,
      Instant confirmedAt,
      Instant estimatedDeliveryAt,
      Instant createdAt,
      Instant updatedAt) {
    this(
        id,
        orderNumber,
        customerId,
        pharmacyId,
        cartId,
        items,
        itemTotalPaise,
        couponCode,
        couponDiscountPaise,
        deliveryFeePaise,
        handlingFeePaise,
        walletAppliedPaise,
        totalPayablePaise,
        paymentMethod,
        paymentStatus,
        razorpayOrderId,
        razorpayPaymentId,
        prescriptionId,
        deliveryAddressId,
        deliveryInstructions,
        status,
        riderId,
        deliveryOtpHash,
        placementIdempotencyKey,
        confirmedAt,
        estimatedDeliveryAt,
        createdAt,
        updatedAt,
        null,
        null,
        null,
        false,
        null,
        null,
        null,
        null,
        null);
  }

  public Order(
      UUID id,
      String orderNumber,
      UUID customerId,
      UUID pharmacyId,
      UUID cartId,
      List<OrderItemSnapshot> items,
      long itemTotalPaise,
      String couponCode,
      long couponDiscountPaise,
      long deliveryFeePaise,
      long handlingFeePaise,
      long walletAppliedPaise,
      long totalPayablePaise,
      PaymentMethod paymentMethod,
      PaymentStatus paymentStatus,
      String razorpayOrderId,
      String razorpayPaymentId,
      UUID prescriptionId,
      UUID deliveryAddressId,
      String deliveryInstructions,
      OrderStatus status,
      UUID riderId,
      String deliveryOtpHash,
      String placementIdempotencyKey,
      Instant confirmedAt,
      Instant estimatedDeliveryAt,
      Instant createdAt,
      Instant updatedAt,
      Instant acceptedAt,
      Instant deliveredAt,
      Instant slaDeadline,
      boolean slaBreached,
      Instant riderAssignedAt,
      Instant otpVerifiedAt,
      Instant readyForPickupAt,
      Instant riderEscalationAt,
      String cancelReason) {
    this.id = id;
    this.orderNumber = orderNumber;
    this.customerId = customerId;
    this.pharmacyId = pharmacyId;
    this.cartId = cartId;
    this.items = items == null ? List.of() : List.copyOf(items);
    this.itemTotalPaise = itemTotalPaise;
    this.couponCode = couponCode;
    this.couponDiscountPaise = couponDiscountPaise;
    this.deliveryFeePaise = deliveryFeePaise;
    this.handlingFeePaise = handlingFeePaise;
    this.walletAppliedPaise = walletAppliedPaise;
    this.totalPayablePaise = totalPayablePaise;
    this.paymentMethod = paymentMethod;
    this.paymentStatus = paymentStatus;
    this.razorpayOrderId = razorpayOrderId;
    this.razorpayPaymentId = razorpayPaymentId;
    this.prescriptionId = prescriptionId;
    this.deliveryAddressId = deliveryAddressId;
    this.deliveryInstructions = deliveryInstructions;
    this.status = status;
    this.riderId = riderId;
    this.deliveryOtpHash = deliveryOtpHash;
    this.placementIdempotencyKey = placementIdempotencyKey;
    this.confirmedAt = confirmedAt;
    this.estimatedDeliveryAt = estimatedDeliveryAt;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
    this.acceptedAt = acceptedAt;
    this.deliveredAt = deliveredAt;
    this.slaDeadline = slaDeadline;
    this.slaBreached = slaBreached;
    this.riderAssignedAt = riderAssignedAt;
    this.otpVerifiedAt = otpVerifiedAt;
    this.readyForPickupAt = readyForPickupAt;
    this.riderEscalationAt = riderEscalationAt;
    this.cancelReason = cancelReason;
  }

  public UUID id() {
    return id;
  }

  public String orderNumber() {
    return orderNumber;
  }

  public UUID customerId() {
    return customerId;
  }

  public UUID pharmacyId() {
    return pharmacyId;
  }

  public UUID cartId() {
    return cartId;
  }

  public List<OrderItemSnapshot> items() {
    return items;
  }

  public long itemTotalPaise() {
    return itemTotalPaise;
  }

  public String couponCode() {
    return couponCode;
  }

  public long couponDiscountPaise() {
    return couponDiscountPaise;
  }

  public long deliveryFeePaise() {
    return deliveryFeePaise;
  }

  public long handlingFeePaise() {
    return handlingFeePaise;
  }

  public long walletAppliedPaise() {
    return walletAppliedPaise;
  }

  public long totalPayablePaise() {
    return totalPayablePaise;
  }

  public PaymentMethod paymentMethod() {
    return paymentMethod;
  }

  public PaymentStatus paymentStatus() {
    return paymentStatus;
  }

  public String razorpayOrderId() {
    return razorpayOrderId;
  }

  public String razorpayPaymentId() {
    return razorpayPaymentId;
  }

  public UUID prescriptionId() {
    return prescriptionId;
  }

  public UUID deliveryAddressId() {
    return deliveryAddressId;
  }

  public String deliveryInstructions() {
    return deliveryInstructions;
  }

  public OrderStatus status() {
    return status;
  }

  public UUID riderId() {
    return riderId;
  }

  public String deliveryOtpHash() {
    return deliveryOtpHash;
  }

  public String placementIdempotencyKey() {
    return placementIdempotencyKey;
  }

  public Instant confirmedAt() {
    return confirmedAt;
  }

  public Instant estimatedDeliveryAt() {
    return estimatedDeliveryAt;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant updatedAt() {
    return updatedAt;
  }

  public Instant acceptedAt() {
    return acceptedAt;
  }

  public Instant deliveredAt() {
    return deliveredAt;
  }

  public Instant slaDeadline() {
    return slaDeadline;
  }

  public boolean slaBreached() {
    return slaBreached;
  }

  public Instant riderAssignedAt() {
    return riderAssignedAt;
  }

  public Instant otpVerifiedAt() {
    return otpVerifiedAt;
  }

  public Instant readyForPickupAt() {
    return readyForPickupAt;
  }

  public Instant riderEscalationAt() {
    return riderEscalationAt;
  }

  public String cancelReason() {
    return cancelReason;
  }

  public void markPaymentPending(String razorpayOrderId, Instant now) {
    this.razorpayOrderId = razorpayOrderId;
    this.status = OrderStatus.PAYMENT_PENDING;
    this.paymentStatus = PaymentStatus.AWAITING_PAYMENT;
    touch(now);
  }

  public void confirm(Instant confirmedAt, Instant estimatedDeliveryAt, String paymentId) {
    this.status = OrderStatus.PENDING_ACCEPTANCE;
    if (paymentMethod == PaymentMethod.COD) {
      this.paymentStatus = PaymentStatus.PENDING_COLLECTION;
    } else {
      this.paymentStatus = PaymentStatus.PAID;
    }
    if (paymentId != null) {
      this.razorpayPaymentId = paymentId;
    }
    this.confirmedAt = confirmedAt;
    this.estimatedDeliveryAt = estimatedDeliveryAt;
    this.slaDeadline = confirmedAt.plus(DELIVERY_SLA);
    touch(confirmedAt);
  }

  public void markCodCollected(Instant now) {
    this.paymentStatus = PaymentStatus.COLLECTED;
    touch(now);
  }

  public void markRefunded(boolean partial, Instant now) {
    this.paymentStatus = partial ? PaymentStatus.PARTIALLY_REFUNDED : PaymentStatus.REFUNDED;
    touch(now);
  }

  public void accept(Instant now) {
    this.status = OrderStatus.ACCEPTED;
    this.acceptedAt = now;
    touch(now);
  }

  public void advanceTo(OrderStatus to, Instant now) {
    this.status = to;
    if (to == OrderStatus.READY_FOR_PICKUP) {
      this.readyForPickupAt = now;
    }
    if (to == OrderStatus.DELIVERED) {
      this.deliveredAt = now;
      if (slaDeadline != null && now.isAfter(slaDeadline)) {
        this.slaBreached = true;
      }
    }
    touch(now);
  }

  public void cancel(String reason, Instant now) {
    this.status = OrderStatus.CANCELLED;
    this.cancelReason = reason;
    touch(now);
  }

  public void assignRider(UUID riderId, Instant now) {
    this.riderId = riderId;
    this.riderAssignedAt = now;
    touch(now);
  }

  public void setDeliveryOtpHash(String hash, Instant now) {
    this.deliveryOtpHash = hash;
    touch(now);
  }

  public void clearDeliveryOtp(Instant now) {
    this.deliveryOtpHash = null;
    this.otpVerifiedAt = now;
    touch(now);
  }

  public void markRiderEscalation(Instant now) {
    this.riderEscalationAt = now;
    touch(now);
  }

  public void markSlaBreached(Instant now) {
    this.slaBreached = true;
    touch(now);
  }

  public boolean isAcceptanceTimedOut(Instant now) {
    if (status != OrderStatus.PENDING_ACCEPTANCE || confirmedAt == null) {
      return false;
    }
    return !now.isBefore(confirmedAt.plus(ACCEPTANCE_WINDOW));
  }

  public boolean needsRiderEscalation(Instant now) {
    if (status != OrderStatus.READY_FOR_PICKUP) {
      return false;
    }
    if (riderId != null || riderEscalationAt != null || readyForPickupAt == null) {
      return false;
    }
    return !now.isBefore(readyForPickupAt.plus(RIDER_ASSIGN_ALERT));
  }

  /** Customer-facing remaining minutes (clamped ≥ 0). */
  public int slaRemainingMinutesClamped(Instant now) {
    return Math.max(0, slaRemainingMinutesRaw(now));
  }

  /** Raw remaining minutes (may be negative after breach). */
  public int slaRemainingMinutesRaw(Instant now) {
    if (slaDeadline == null) {
      return 0;
    }
    long seconds = Duration.between(now, slaDeadline).getSeconds();
    if (seconds >= 0) {
      return (int) Math.ceil(seconds / 60.0);
    }
    return (int) Math.floor(seconds / 60.0);
  }

  public boolean slaRisk(Instant now) {
    if (status.isTerminal() || slaDeadline == null) {
      return false;
    }
    long remainingSec = Duration.between(now, slaDeadline).getSeconds();
    return remainingSec > 0 && remainingSec < SLA_RISK_THRESHOLD.getSeconds();
  }

  public Integer etaMinutes(Instant now) {
    if (estimatedDeliveryAt == null || status.isTerminal()) {
      return null;
    }
    long seconds = Duration.between(now, estimatedDeliveryAt).getSeconds();
    return (int) Math.max(0L, Math.ceil(seconds / 60.0));
  }

  public void touch(Instant now) {
    this.updatedAt = now;
  }

  public List<OrderItemSnapshot> mutableItemsCopy() {
    return new ArrayList<>(items);
  }
}
