package com.nammamedmate.order.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.order.application.port.out.OrderCancellationStore;
import com.nammamedmate.order.application.port.out.OrderStore;
import com.nammamedmate.order.application.port.out.RazorpayPaymentPort;
import com.nammamedmate.order.application.port.out.RefundInitiatorPort;
import com.nammamedmate.order.application.port.out.RefundStore;
import com.nammamedmate.order.application.port.out.WalletPort;
import com.nammamedmate.order.domain.ActorType;
import com.nammamedmate.order.domain.CancelledByType;
import com.nammamedmate.order.domain.Order;
import com.nammamedmate.order.domain.OrderCancellation;
import com.nammamedmate.order.domain.PaymentMethod;
import com.nammamedmate.order.domain.PaymentStatus;
import com.nammamedmate.order.domain.Refund;
import com.nammamedmate.order.domain.RefundIssuedByType;
import com.nammamedmate.order.domain.RefundStatus;
import com.nammamedmate.order.domain.RefundTo;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefundService implements RefundInitiatorPort {

  private final RefundStore refunds;
  private final OrderCancellationStore cancellations;
  private final OrderStore orders;
  private final RazorpayPaymentPort razorpay;
  private final WalletPort wallet;
  private final Clock clock;

  public RefundService(
      RefundStore refunds,
      OrderCancellationStore cancellations,
      OrderStore orders,
      RazorpayPaymentPort razorpay,
      WalletPort wallet,
      Clock clock) {
    this.refunds = refunds;
    this.cancellations = cancellations;
    this.orders = orders;
    this.razorpay = razorpay;
    this.wallet = wallet;
    this.clock = clock;
  }

  @Override
  @Transactional
  public RefundPlan initiate(
      Order order, String reason, ActorType cancelledByType, UUID cancelledById) {
    Instant now = clock.instant();
    persistCancellation(order, reason, toCancelledBy(cancelledByType), cancelledById, now);
    if (!isAutoRefundDue(order)) {
      return new RefundPlan(false, 0L, null);
    }
    Refund primary = null;
    if (order.walletAppliedPaise() > 0) {
      primary =
          executeRefund(
              order,
              order.walletAppliedPaise(),
              RefundTo.WALLET,
              reason,
              null,
              null,
              issuedByType(cancelledByType),
              null,
              now);
    }
    long online = order.totalPayablePaise();
    if (online > 0 && isPayableRefundable(order)) {
      RefundTo to = defaultRefundTo(order);
      primary =
          executeRefund(
              order, online, to, reason, null, null, issuedByType(cancelledByType), null, now);
    }
    if (primary == null) {
      return new RefundPlan(false, 0L, null);
    }
    return new RefundPlan(true, primary.amountPaise(), primary.refundTo().name());
  }

  @Transactional
  public Refund issueManual(
      Order order,
      long amountPaise,
      RefundTo refundTo,
      String reason,
      String notes,
      UUID issuedBy,
      String idempotencyKey) {
    String key = requireIdempotencyKey(idempotencyKey);
    var replay = refunds.findByIdempotencyKey(key);
    if (replay.isPresent()) {
      return replay.get();
    }
    if (amountPaise <= 0) {
      throw new AppException("VALIDATION_ERROR", "amount must be positive", 400);
    }
    long remaining = remainingRefundablePaise(order);
    if (amountPaise > remaining) {
      throw new AppException(
          "REFUND_EXCEEDS_REMAINING_REFUNDABLE", "Refund exceeds remaining refundable amount", 422);
    }
    if (refundTo == RefundTo.SOURCE) {
      long maxSource = onlinePortionMax(order) - sourceRefundedPaise(order.id());
      if (amountPaise > maxSource) {
        throw new AppException(
            "REFUND_EXCEEDS_REMAINING_REFUNDABLE",
            "SOURCE refund exceeds online payment portion",
            422);
      }
    }
    return executeRefund(
        order,
        amountPaise,
        refundTo,
        reason,
        notes,
        issuedBy,
        RefundIssuedByType.ADMIN,
        key,
        clock.instant());
  }

  @Transactional
  public Refund issueOnAdminCancel(
      Order order, long amountPaise, RefundTo refundTo, String reason, UUID issuedBy, Instant now) {
    if (amountPaise <= 0) {
      return null;
    }
    if (order.paymentMethod() == PaymentMethod.COD
        && order.paymentStatus() != PaymentStatus.COLLECTED) {
      return null;
    }
    if (amountPaise > order.totalPayablePaise()) {
      throw new AppException(
          "REFUND_EXCEEDS_ORDER_TOTAL", "refund_amount exceeds order total payable", 422);
    }
    if (refundTo == RefundTo.SOURCE && amountPaise > onlinePortionMax(order)) {
      throw new AppException(
          "REFUND_EXCEEDS_ORDER_TOTAL", "SOURCE refund exceeds online payment portion", 422);
    }
    return executeRefund(
        order, amountPaise, refundTo, reason, null, issuedBy, RefundIssuedByType.ADMIN, null, now);
  }

  /** Reverse wallet debit applied at checkout (not bounded by total_payable). */
  @Transactional
  public Refund reverseWalletApplied(Order order, String reason, UUID issuedBy, Instant now) {
    if (order.walletAppliedPaise() <= 0) {
      return null;
    }
    return executeRefund(
        order,
        order.walletAppliedPaise(),
        RefundTo.WALLET,
        reason,
        null,
        issuedBy,
        issuedBy == null ? RefundIssuedByType.SYSTEM : RefundIssuedByType.ADMIN,
        null,
        now);
  }

  public void persistCancellation(
      Order order, String reason, CancelledByType byType, UUID byId, Instant at) {
    if (cancellations.findByOrderId(order.id()).isPresent()) {
      return;
    }
    String trimmed = reason == null || reason.isBlank() ? "CANCELLED" : reason.trim();
    if (trimmed.length() > 300) {
      trimmed = trimmed.substring(0, 300);
    }
    cancellations.insert(new OrderCancellation(Ids.newId(), order.id(), byType, byId, trimmed, at));
  }

  public long remainingRefundablePaise(Order order) {
    return Math.max(0L, order.totalPayablePaise() - refunds.sumSuccessfulPaise(order.id()));
  }

  public long alreadyRefundedPaise(UUID orderId) {
    return refunds.sumSuccessfulPaise(orderId);
  }

  public RefundTo recommendRefundTo(Order order) {
    return defaultRefundTo(order);
  }

  public static long onlinePortionMax(Order order) {
    if (order.paymentMethod() == PaymentMethod.COD
        || order.paymentMethod() == PaymentMethod.WALLET) {
      return 0L;
    }
    return order.totalPayablePaise();
  }

  @Transactional
  public Map<String, Object> handleRefundProcessed(JsonNode root) {
    JsonNode entity = root.path("payload").path("refund").path("entity");
    String razorpayRefundId = text(entity, "id");
    if (razorpayRefundId == null) {
      return Map.of("ignored", true);
    }
    Instant now = clock.instant();
    Refund refund = refunds.findByRazorpayRefundId(razorpayRefundId).orElse(null);
    if (refund == null) {
      return Map.of("ignored", true);
    }
    if (refund.status() == RefundStatus.PROCESSED) {
      Map<String, Object> done = new LinkedHashMap<>();
      done.put("refund_id", refund.id().toString());
      done.put("status", refund.status().name());
      return done;
    }
    refund.markProcessed(now);
    refunds.update(refund);
    refreshOrderPaymentStatus(refund.orderId(), now);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("refund_id", refund.id().toString());
    data.put("status", RefundStatus.PROCESSED.name());
    return data;
  }

  private Refund executeRefund(
      Order order,
      long amountPaise,
      RefundTo refundTo,
      String reason,
      String notes,
      UUID issuedBy,
      RefundIssuedByType issuedByType,
      String idempotencyKey,
      Instant now) {
    UUID id = Ids.newId();
    RefundStatus status = RefundStatus.INITIATED;
    String rzRefundId = null;
    UUID walletTxId = null;
    Instant processedAt = null;

    if (refundTo == RefundTo.WALLET) {
      String key =
          idempotencyKey != null ? idempotencyKey : "order-refund-" + order.id() + "-" + id;
      walletTxId =
          wallet.creditForRefund(
              order.customerId(),
              order.id(),
              amountPaise,
              reason == null ? "Order refund" : reason,
              key);
      status = RefundStatus.PROCESSED;
      processedAt = now;
    } else {
      String paymentId = order.razorpayPaymentId();
      if (paymentId == null || paymentId.isBlank()) {
        throw new AppException(
            "VALIDATION_ERROR", "Order has no Razorpay payment for SOURCE refund", 422);
      }
      // ponytail: sync Razorpay SOURCE refund in-request (stub client). Ceiling: TX/provider
      // orphan risk under live gateway — upgrade to INITIATED+outbox→worker then webhook PROCESSED.
      RazorpayPaymentPort.RefundResult rz = razorpay.refund(paymentId, amountPaise);
      rzRefundId = rz.razorpayRefundId();
    }

    Refund refund =
        new Refund(
            id,
            order.id(),
            amountPaise,
            refundTo,
            reason == null ? "REFUND" : truncate(reason, 300),
            notes == null || notes.isBlank() ? null : truncate(notes, 500),
            status,
            issuedBy,
            issuedByType,
            rzRefundId,
            walletTxId,
            processedAt,
            null,
            idempotencyKey,
            now);
    refunds.insert(refund);
    refreshOrderPaymentStatus(order.id(), now);
    return refund;
  }

  private void refreshOrderPaymentStatus(UUID orderId, Instant now) {
    Order order = orders.findById(orderId).orElse(null);
    if (order == null) {
      return;
    }
    long refunded = refunds.sumSuccessfulPaise(orderId);
    if (refunded <= 0) {
      return;
    }
    boolean partial = refunded < order.totalPayablePaise();
    order.markRefunded(partial, now);
    orders.update(order);
  }

  private static boolean isAutoRefundDue(Order order) {
    if (order.paymentMethod() == PaymentMethod.COD) {
      return order.paymentStatus() == PaymentStatus.COLLECTED || order.walletAppliedPaise() > 0;
    }
    return order.paymentStatus() == PaymentStatus.PAID
        || order.paymentStatus() == PaymentStatus.PARTIALLY_REFUNDED
        || order.paymentStatus() == PaymentStatus.REFUNDED
        || order.walletAppliedPaise() > 0;
  }

  private static boolean isPayableRefundable(Order order) {
    if (order.paymentMethod() == PaymentMethod.COD) {
      return order.paymentStatus() == PaymentStatus.COLLECTED;
    }
    return order.paymentStatus() == PaymentStatus.PAID
        || order.paymentStatus() == PaymentStatus.PARTIALLY_REFUNDED;
  }

  static RefundTo defaultRefundTo(Order order) {
    if (order.paymentMethod() == PaymentMethod.WALLET
        || order.paymentMethod() == PaymentMethod.COD) {
      return RefundTo.WALLET;
    }
    return RefundTo.SOURCE;
  }

  private long sourceRefundedPaise(UUID orderId) {
    return refunds.listByOrderId(orderId).stream()
        .filter(r -> r.refundTo() == RefundTo.SOURCE)
        .filter(r -> r.status() == RefundStatus.INITIATED || r.status() == RefundStatus.PROCESSED)
        .mapToLong(Refund::amountPaise)
        .sum();
  }

  private static RefundIssuedByType issuedByType(ActorType actorType) {
    if (actorType == ActorType.ADMIN) {
      return RefundIssuedByType.ADMIN;
    }
    if (actorType == ActorType.PHARMACY) {
      return RefundIssuedByType.PHARMACY;
    }
    return RefundIssuedByType.SYSTEM;
  }

  private static CancelledByType toCancelledBy(ActorType actorType) {
    if (actorType == null) {
      return CancelledByType.SYSTEM;
    }
    return switch (actorType) {
      case CUSTOMER -> CancelledByType.CUSTOMER;
      case PHARMACY -> CancelledByType.PHARMACY;
      case ADMIN -> CancelledByType.ADMIN;
      case SYSTEM, RIDER -> CancelledByType.SYSTEM;
    };
  }

  private static String requireIdempotencyKey(String key) {
    if (key == null || key.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "Idempotency-Key is required", 400);
    }
    String trimmed = key.trim();
    if (trimmed.length() > 128) {
      throw new AppException("VALIDATION_ERROR", "Idempotency-Key max 128 characters", 400);
    }
    return trimmed;
  }

  private static String text(JsonNode node, String field) {
    JsonNode n = node.get(field);
    if (n == null || n.isNull()) {
      return null;
    }
    String v = n.asText();
    return v.isBlank() ? null : v;
  }

  private static String truncate(String s, int max) {
    String t = s.trim();
    return t.length() <= max ? t : t.substring(0, max);
  }

  static RefundTo parseRefundTo(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "refund_to is required", 400);
    }
    try {
      return RefundTo.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new AppException("VALIDATION_ERROR", "refund_to must be SOURCE or WALLET", 400);
    }
  }
}
